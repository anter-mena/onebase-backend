package com.onebase.whatsapp;

import com.onebase.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Meta's WhatsApp Cloud API: send messages and files, mark as read, fetch files.
 *
 * <p>Meta's error codes become sentences: the two that matter most are the 24-hour
 * window (131047: only a template may start a conversation again) and, with the
 * test number, a recipient that is not in its allowed list (131030).
 */
@Component
public class WhatsAppCloudApi {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppCloudApi.class);
	static final long MAX_MEDIA_BYTES = 16L * 1024 * 1024;

	public record MediaFile(byte[] bytes, String mime) {
	}

	private final WhatsAppSettings settings;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	public WhatsAppCloudApi(WhatsAppSettings settings, ObjectMapper json) {
		this.settings = settings;
		this.json = json;
	}

	/** Returns Meta's message id (wamid…). */
	public String sendText(String to, String text) {
		ObjectNode body = base(to, "text");
		body.putObject("text").put("body", text).put("preview_url", false);
		return messageId(post(messagesUrl(), body));
	}

	public String sendTemplate(String to, String name, String language) {
		ObjectNode body = base(to, "template");
		ObjectNode template = body.putObject("template");
		template.put("name", name);
		template.putObject("language").put("code", language);
		return messageId(post(messagesUrl(), body));
	}

	/** {@code kind}: image, document, audio or video. */
	public String sendMedia(String to, String kind, String mediaId, String caption, String filename) {
		ObjectNode body = base(to, kind);
		ObjectNode media = body.putObject(kind);
		media.put("id", mediaId);
		if (caption != null && !caption.isBlank() && !kind.equals("audio")) media.put("caption", caption);
		if (kind.equals("document") && filename != null) media.put("filename", filename);
		return messageId(post(messagesUrl(), body));
	}

	public void markRead(String waMessageId) {
		ObjectNode body = json.createObjectNode();
		body.put("messaging_product", "whatsapp");
		body.put("status", "read");
		body.put("message_id", waMessageId);
		post(messagesUrl(), body);
	}

	/** Uploads a file to Meta and returns its media id. */
	public String upload(byte[] bytes, String mime, String filename) {
		String boundary = "----onebase" + UUID.randomUUID();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			out.write(field(boundary, "messaging_product", "whatsapp"));
			out.write(field(boundary, "type", mime));
			out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
				+ filename.replace("\"", "") + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(bytes);
			out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		HttpRequest request = authorized(URI.create(graph() + settings.phoneNumberId() + "/media"))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
			.build();
		return call(request).path("id").asString();
	}

	/** A received file: Meta gives a short-lived address, fetched with the same token. */
	public MediaFile download(String mediaId) {
		JsonNode info = call(authorized(URI.create(graph() + mediaId)).GET().build());
		String url = info.path("url").asString("");
		if (url.isEmpty()) throw ApiException.notFound("WhatsApp no longer has this file.");
		try {
			HttpResponse<byte[]> response = http.send(authorized(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) throw ApiException.notFound("WhatsApp no longer has this file.");
			if (response.body().length > MAX_MEDIA_BYTES * 6) throw ApiException.badRequest("This file is too large to download here.");
			return new MediaFile(response.body(), info.path("mime_type").asString("application/octet-stream"));
		} catch (IOException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		}
	}

	// ── Plumbing ──────────────────────────────────────────────────────────────

	private String graph() {
		return "https://graph.facebook.com/" + settings.apiVersion() + "/";
	}

	private String messagesUrl() {
		return graph() + settings.phoneNumberId() + "/messages";
	}

	private ObjectNode base(String to, String type) {
		ObjectNode body = json.createObjectNode();
		body.put("messaging_product", "whatsapp");
		body.put("recipient_type", "individual");
		body.put("to", to);
		body.put("type", type);
		return body;
	}

	private JsonNode post(String url, ObjectNode body) {
		return call(authorized(URI.create(url))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
			.build());
	}

	private HttpRequest.Builder authorized(URI uri) {
		if (!settings.connected()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp is not connected yet.");
		}
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + settings.token());
	}

	private JsonNode call(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			JsonNode body = response.body() == null || response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
			if (response.statusCode() / 100 == 2) return body;
			JsonNode error = body.path("error");
			int code = error.path("code").asInt(0);
			log.warn("WhatsApp API {} answered {}: {}", request.uri().getPath(), response.statusCode(), response.body());
			throw new ApiException(HttpStatus.BAD_GATEWAY, explain(code, error.path("message").asString("")));
		} catch (IOException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		}
	}

	/** Meta's codes, in words the person can act on. */
	static String explain(int code, String metaMessage) {
		return switch (code) {
			case 131047 -> "More than 24 hours since the client's last message: only a template can be sent now.";
			case 131030 -> "With the test number, WhatsApp only sends to numbers added in Meta (API Setup → To).";
			case 131026 -> "This number cannot receive WhatsApp messages.";
			case 190 -> "The WhatsApp token on the server is no longer valid.";
			case 100 -> metaMessage.toLowerCase().contains("authorization")
				? "The WhatsApp key has no access to this number: in Meta, give the OneBase system user Full control of its WhatsApp account."
				: "WhatsApp refused the message: " + metaMessage;
			case 131056 -> "Too many messages to this number at once. Please wait a moment.";
			default -> "WhatsApp refused the message" + (metaMessage.isBlank() ? "." : ": " + metaMessage);
		};
	}

	private String messageId(JsonNode response) {
		JsonNode messages = response.path("messages");
		return messages.isArray() && !messages.isEmpty() ? messages.get(0).path("id").asString(null) : null;
	}

	private static byte[] field(String boundary, String name, String value) {
		return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
			.getBytes(StandardCharsets.UTF_8);
	}
}
