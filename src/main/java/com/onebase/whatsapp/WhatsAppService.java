package com.onebase.whatsapp;

import com.onebase.client.ClientService;
import com.onebase.common.ApiException;
import com.onebase.security.AuthPrincipal;
import com.onebase.whatsapp.WhatsAppMessage.Direction;
import com.onebase.whatsapp.WhatsAppMessage.Status;
import com.onebase.whatsapp.WhatsAppMessage.Type;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * The WhatsApp Inbox: what arrives through Meta's webhook is saved here, and what
 * Admins and Commercials send goes out through the Cloud API.
 *
 * <p><b>The 24-hour rule.</b> Free text and files only within 24 hours of the
 * client's last message; after that WhatsApp accepts only an approved template,
 * which this screen offers instead (Meta's "hello_world" on the test number).
 */
@Service
public class WhatsAppService {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
	static final Duration WINDOW = Duration.ofHours(24);
	static final int MAX_TEXT = 4096;

	public record ConversationView(long id, String waId, String phone, String name, int unread, Instant lastMessageAt,
			String preview, boolean windowOpen, Instant windowEndsAt, Long clientId) {
	}

	public record MessageView(long id, String direction, String type, String body, boolean hasMedia, String mediaMime,
			String mediaFilename, String status, String error, Instant createdAt) {
	}

	public record StatusView(boolean connected, long unread) {
	}

	private final WhatsAppConversationRepository conversations;
	private final WhatsAppMessageRepository messages;
	private final WhatsAppCloudApi api;
	private final WhatsAppSettings settings;
	private final ClientService clients;

	public WhatsAppService(WhatsAppConversationRepository conversations, WhatsAppMessageRepository messages,
			WhatsAppCloudApi api, WhatsAppSettings settings, ClientService clients) {
		this.conversations = conversations;
		this.messages = messages;
		this.api = api;
		this.settings = settings;
		this.clients = clients;
	}

	// ── What Meta sends us ─────────────────────────────────────────────────────

	/** One webhook call: new messages and the statuses of ours. Unknown numbers and repeats are ignored. */
	@Transactional
	public void handleWebhook(JsonNode payload) {
		for (JsonNode entry : payload.path("entry")) {
			for (JsonNode change : entry.path("changes")) {
				if (!"messages".equals(change.path("field").asString(""))) continue;
				JsonNode value = change.path("value");
				String phoneNumberId = value.path("metadata").path("phone_number_id").asString("");
				if (!settings.phoneNumberId().isEmpty() && !settings.phoneNumberId().equals(phoneNumberId)) continue;
				for (JsonNode m : value.path("messages")) receive(m, contactName(value, m.path("from").asString("")));
				for (JsonNode s : value.path("statuses")) status(s);
			}
		}
	}

	private void receive(JsonNode m, String name) {
		String id = m.path("id").asString("");
		String from = m.path("from").asString("");
		if (id.isEmpty() || from.isEmpty() || messages.existsByWaMessageId(id)) return;
		Instant at = Instant.ofEpochSecond(m.path("timestamp").asLong(Instant.now().getEpochSecond()));
		String kind = m.path("type").asString("");

		Type type;
		String body = null;
		JsonNode media = null;
		switch (kind) {
			case "text" -> {
				type = Type.TEXT;
				body = m.path("text").path("body").asString("");
			}
			case "image", "document", "audio", "video", "sticker" -> {
				type = Type.valueOf(kind.toUpperCase(Locale.ROOT));
				media = m.path(kind);
				body = media.path("caption").asString(null);
			}
			case "button" -> {
				type = Type.TEXT;
				body = m.path("button").path("text").asString("");
			}
			case "interactive" -> {
				type = Type.TEXT;
				JsonNode reply = m.path("interactive").path(m.path("interactive").path("type").asString(""));
				body = reply.path("title").asString("");
			}
			case "reaction" -> {
				type = Type.OTHER;
				body = "Reacted " + m.path("reaction").path("emoji").asString("");
			}
			case "location" -> {
				type = Type.OTHER;
				JsonNode loc = m.path("location");
				body = "Location: " + loc.path("latitude").asString("") + ", " + loc.path("longitude").asString("");
			}
			default -> {
				type = Type.OTHER;
				body = "(" + (kind.isEmpty() ? "unsupported" : kind) + " message — open WhatsApp to see it)";
			}
		}

		WhatsAppConversation conversation = conversations.findByWaId(from)
			.orElseGet(() -> conversations.save(new WhatsAppConversation(from, name)));
		messages.save(WhatsAppMessage.incoming(conversation.getId(), id, type, body,
			media == null ? null : media.path("id").asString(null),
			media == null ? null : media.path("mime_type").asString(null),
			media == null ? null : media.path("filename").asString(null), at));
		conversation.received(name, preview(type, body, media == null ? null : media.path("filename").asString(null)), at);
		// Every number is a client: found by its number, or made now as New.
		Long clientId = clients.fromWhatsApp(from, name);
		if (clientId != null && !clientId.equals(conversation.getClientId())) conversation.linkClient(clientId);
		conversations.save(conversation);
	}

	private void status(JsonNode s) {
		String id = s.path("id").asString("");
		Status next = switch (s.path("status").asString("")) {
			case "sent" -> Status.SENT;
			case "delivered" -> Status.DELIVERED;
			case "read" -> Status.READ;
			case "failed" -> Status.FAILED;
			default -> null;
		};
		if (id.isEmpty() || next == null) return;
		messages.findByWaMessageId(id).ifPresent(message -> {
			JsonNode error = s.path("errors").path(0);
			String why = error.isMissingNode() ? null
				: WhatsAppCloudApi.explain(error.path("code").asInt(0), error.path("title").asString(""));
			message.advance(next, why);
			messages.save(message);
		});
	}

	private static String contactName(JsonNode value, String waId) {
		for (JsonNode contact : value.path("contacts")) {
			if (waId.equals(contact.path("wa_id").asString(""))) return contact.path("profile").path("name").asString(null);
		}
		return null;
	}

	// ── The screen ─────────────────────────────────────────────────────────────

	public StatusView status() {
		return new StatusView(settings.connected(), conversations.totalUnread());
	}

	public List<ConversationView> list(String q, String filter) {
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		String digits = needle.replaceAll("[^0-9]", "");
		return conversations.findAllNewestFirst().stream()
			.filter(c -> !"unread".equals(filter) || c.getUnreadCount() > 0)
			.filter(c -> !"read".equals(filter) || c.getUnreadCount() == 0)
			.filter(c -> needle.isEmpty()
				|| (c.getContactName() != null && c.getContactName().toLowerCase(Locale.ROOT).contains(needle))
				|| (!digits.isEmpty() && c.getWaId().contains(digits))
				|| (c.getLastPreview() != null && c.getLastPreview().toLowerCase(Locale.ROOT).contains(needle)))
			.map(WhatsAppService::view)
			.toList();
	}

	/** The messages, oldest first. Opening a conversation marks it read, here and on the client's phone. */
	public List<MessageView> messages(long conversationId, boolean markRead) {
		WhatsAppConversation conversation = find(conversationId);
		if (markRead && conversation.getUnreadCount() > 0) {
			conversation.markRead();
			conversations.save(conversation);
			messages.findFirstByConversationIdAndDirectionOrderByCreatedAtDescIdDesc(conversationId, Direction.IN)
				.map(WhatsAppMessage::getWaMessageId)
				.ifPresent(wamid -> {
					try {
						api.markRead(wamid);
					} catch (ApiException e) {
						// The blue ticks are a courtesy; the screen does not depend on them.
						log.info("Could not mark {} read on WhatsApp: {}", wamid, e.getMessage());
					}
				});
		}
		return messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream().map(WhatsAppService::view).toList();
	}

	public MessageView sendText(AuthPrincipal user, long conversationId, String text) {
		String clean = text == null ? "" : text.strip();
		if (clean.isEmpty()) throw ApiException.badRequest("Write a message first.");
		if (clean.length() > MAX_TEXT) throw ApiException.badRequest("A WhatsApp message can be 4096 characters at most.");
		WhatsAppConversation conversation = find(conversationId);
		requireWindow(conversation);
		WhatsAppMessage message = messages.save(WhatsAppMessage.outgoing(conversationId, Type.TEXT, clean, null, null, null, user.userId()));
		return deliver(conversation, message, () -> api.sendText(conversation.getWaId(), clean));
	}

	/** Outside the 24-hour window: the approved template, which reopens the conversation when the client answers. */
	public MessageView sendTemplate(AuthPrincipal user, long conversationId) {
		WhatsAppConversation conversation = find(conversationId);
		WhatsAppMessage message = messages.save(WhatsAppMessage.outgoing(conversationId, Type.TEMPLATE,
			"Template: " + settings.template(), null, null, null, user.userId()));
		return deliver(conversation, message, () -> api.sendTemplate(conversation.getWaId(), settings.template(), "en_US"));
	}

	public MessageView sendFile(AuthPrincipal user, long conversationId, MultipartFile file, String caption) {
		if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose a file to send.");
		if (file.getSize() > WhatsAppCloudApi.MAX_MEDIA_BYTES) throw ApiException.badRequest("Files can be 16 MB at most.");
		WhatsAppConversation conversation = find(conversationId);
		requireWindow(conversation);
		String mime = file.getContentType() == null || file.getContentType().isBlank() ? "application/octet-stream" : file.getContentType();
		String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename().replaceAll("[\\r\\n\\\\/]", "_");
		String kind = kindOf(mime);
		Type type = Type.valueOf(kind.toUpperCase(Locale.ROOT));
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw ApiException.badRequest("The file could not be read. Please choose it again.");
		}
		String text = caption == null || caption.isBlank() ? null : caption.strip();
		WhatsAppMessage message = messages.save(WhatsAppMessage.outgoing(conversationId, type, text, null, mime, name, user.userId()));
		return deliver(conversation, message, () -> {
			String mediaId = api.upload(bytes, mime, name);
			message.uploaded(mediaId);
			return api.sendMedia(conversation.getWaId(), kind, mediaId, text, name);
		});
	}

	public WhatsAppCloudApi.MediaFile media(long messageId) {
		WhatsAppMessage message = messages.findById(messageId).orElseThrow(() -> ApiException.notFound("This message does not exist."));
		if (message.getMediaId() == null) throw ApiException.notFound("This message has no file to download.");
		return api.download(message.getMediaId());
	}

	public String fileName(long messageId) {
		return messages.findById(messageId).map(WhatsAppMessage::getMediaFilename).orElse(null);
	}

	// ── Helpers ─────────────────────────────────────────────────────────────────

	@FunctionalInterface
	private interface Send {
		String run();
	}

	private MessageView deliver(WhatsAppConversation conversation, WhatsAppMessage message, Send send) {
		try {
			message.accepted(send.run());
		} catch (ApiException e) {
			message.failed(e.getMessage());
			messages.save(message);
			throw e;
		}
		messages.save(message);
		conversation.sent(preview(message.getType(), message.getBody(), message.getMediaFilename()), message.getCreatedAt());
		conversations.save(conversation);
		return view(message);
	}

	private static void requireWindow(WhatsAppConversation conversation) {
		if (!windowOpen(conversation)) {
			throw new ApiException(HttpStatus.CONFLICT,
				"More than 24 hours since the client's last message: only a template can be sent now.");
		}
	}

	static boolean windowOpen(WhatsAppConversation c) {
		return c.getLastInboundAt() != null && c.getLastInboundAt().plus(WINDOW).isAfter(Instant.now());
	}

	/** Meta's media kinds by type. Anything it does not play inline goes as a document. */
	static String kindOf(String mime) {
		String m = mime.toLowerCase(Locale.ROOT);
		if (m.equals("image/jpeg") || m.equals("image/png")) return "image";
		if (m.equals("video/mp4") || m.equals("video/3gpp")) return "video";
		if (m.startsWith("audio/")) return "audio";
		return "document";
	}

	private WhatsAppConversation find(long id) {
		return conversations.findById(id).orElseThrow(() -> ApiException.notFound("This conversation does not exist."));
	}

	private static String preview(Type type, String body, String filename) {
		if (body != null && !body.isBlank()) return body.replaceAll("\\s+", " ").strip();
		return switch (type) {
			case IMAGE -> "📷 Photo";
			case VIDEO -> "🎬 Video";
			case AUDIO -> "🎤 Voice message";
			case STICKER -> "Sticker";
			case DOCUMENT -> "📄 " + (filename == null ? "Document" : filename);
			default -> "";
		};
	}

	private static ConversationView view(WhatsAppConversation c) {
		Instant ends = c.getLastInboundAt() == null ? null : c.getLastInboundAt().plus(WINDOW);
		String phone = "+" + c.getWaId();
		return new ConversationView(c.getId(), c.getWaId(), phone, c.getContactName() == null ? phone : c.getContactName(),
			c.getUnreadCount(), c.getLastMessageAt(), c.getLastPreview(), windowOpen(c), ends, c.getClientId());
	}

	private static MessageView view(WhatsAppMessage m) {
		return new MessageView(m.getId(), m.getDirection().name(), m.getType().name(), m.getBody(),
			m.getMediaId() != null,
			m.getMediaMime(), m.getMediaFilename(), m.getStatus().name(), m.getError(), m.getCreatedAt());
	}
}
