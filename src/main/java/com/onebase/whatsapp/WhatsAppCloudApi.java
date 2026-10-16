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
import java.util.Map;
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

	/** The largest received file we download (and keep): Meta takes documents up to 100 MB. */
	static final long MAX_DOWNLOAD_BYTES = MAX_MEDIA_BYTES * 6;

	public record MediaFile(byte[] bytes, String mime) {
	}

	/** Meta refused a call: its HTTP status and error code, with the sentence for the person. */
	static class MetaException extends ApiException {
		final int httpStatus;
		final int code;
		final int subcode;

		MetaException(int httpStatus, int code, int subcode, String message) {
			super(HttpStatus.BAD_GATEWAY, message);
			this.httpStatus = httpStatus;
			this.code = code;
			this.subcode = subcode;
		}
	}

	/** Meta has deleted the file (it keeps them for a limited time): nothing to retry. */
	public static class MediaGoneException extends ApiException {
		public MediaGoneException() {
			super(HttpStatus.NOT_FOUND, "This file is no longer available: WhatsApp keeps files only for a limited time.");
		}
	}

	/** A received file larger than {@link #MAX_DOWNLOAD_BYTES}: nothing to retry. */
	public static class MediaTooLargeException extends ApiException {
		public MediaTooLargeException() {
			super(HttpStatus.BAD_REQUEST, "This file is too large to download here.");
		}
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

	/** A message with buttons; {@code interactive} is WhatsApp's {@code interactive} object (see {@link TemplateMessages}). */
	public String sendInteractive(String to, Map<String, Object> interactive) {
		ObjectNode body = base(to, "interactive");
		body.set("interactive", json.valueToTree(interactive));
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

	/**
	 * A received file: Meta gives a short-lived address, fetched with the same token.
	 * {@link MediaGoneException} when Meta no longer has it (it keeps files for a limited time).
	 */
	public MediaFile download(String mediaId) {
		JsonNode info;
		try {
			info = call(authorized(URI.create(graph() + mediaId)).GET().build());
		} catch (MetaException e) {
			if (gone(e)) throw new MediaGoneException();
			throw e;
		}
		String url = info.path("url").asString("");
		if (url.isEmpty()) throw new MediaGoneException();
		try {
			HttpResponse<byte[]> response = http.send(authorized(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() == 404 || response.statusCode() == 410) throw new MediaGoneException();
			if (response.statusCode() != 200) {
				throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
			}
			if (response.body().length > MAX_DOWNLOAD_BYTES) throw new MediaTooLargeException();
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
			throw new MetaException(response.statusCode(), code, error.path("error_subcode").asInt(0),
				explain(code, error.path("message").asString("")));
		} catch (IOException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.BAD_GATEWAY, "WhatsApp did not answer. Please try again in a moment.");
		}
	}

	/** "Object with ID … does not exist" (code 100, subcode 33) or a plain 404: Meta has deleted the file. */
	static boolean gone(MetaException e) {
		return e.httpStatus == 404 || e.subcode == 33 || (e.code == 100 && e.getMessage().contains("does not exist"));
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
