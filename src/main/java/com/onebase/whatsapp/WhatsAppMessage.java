package com.onebase.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One message, received (IN) or sent (OUT). */
@Entity
@Table(name = "whatsapp_messages")
public class WhatsAppMessage {

	public enum Direction { IN, OUT }

	public enum Type { TEXT, IMAGE, DOCUMENT, AUDIO, VIDEO, STICKER, TEMPLATE, OTHER }

	/** RECEIVED for incoming; SENDING, SENT, DELIVERED, READ (or FAILED) for outgoing. */
	public enum Status { RECEIVED, SENDING, SENT, DELIVERED, READ, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "conversation_id", nullable = false)
	private Long conversationId;

	@Column(name = "wa_message_id", unique = true)
	private String waMessageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Direction direction;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Column(columnDefinition = "text")
	private String body;

	@Column(name = "media_id")
	private String mediaId;

	@Column(name = "media_mime")
	private String mediaMime;

	@Column(name = "media_filename")
	private String mediaFilename;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	private String error;

	@Column(name = "sent_by")
	private Long sentBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected WhatsAppMessage() {
	}

	static WhatsAppMessage incoming(long conversationId, String waMessageId, Type type, String body, String mediaId,
			String mediaMime, String mediaFilename, Instant at) {
		WhatsAppMessage m = new WhatsAppMessage();
		m.conversationId = conversationId;
		m.waMessageId = waMessageId;
		m.direction = Direction.IN;
		m.type = type;
		m.body = body;
		m.mediaId = mediaId;
		m.mediaMime = mediaMime;
		m.mediaFilename = mediaFilename;
		m.status = Status.RECEIVED;
		m.createdAt = at;
		return m;
	}

	static WhatsAppMessage outgoing(long conversationId, Type type, String body, String mediaId, String mediaMime,
			String mediaFilename, Long sentBy) {
		WhatsAppMessage m = new WhatsAppMessage();
		m.conversationId = conversationId;
		m.direction = Direction.OUT;
		m.type = type;
		m.body = body;
		m.mediaId = mediaId;
		m.mediaMime = mediaMime;
		m.mediaFilename = mediaFilename;
		m.status = Status.SENDING;
		m.sentBy = sentBy;
		return m;
	}

	/** Our own file, once Meta has it: kept so it can be downloaded from the conversation too. */
	void uploaded(String mediaId) {
		this.mediaId = mediaId;
	}

	void accepted(String waMessageId) {
		this.waMessageId = waMessageId;
		if (status == Status.SENDING) status = Status.SENT;
	}

	void failed(String error) {
		this.status = Status.FAILED;
		this.error = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
	}

	/** Statuses only move forward: a late "delivered" never undoes "read". */
	void advance(Status next, String error) {
		if (next == Status.FAILED) {
			failed(error);
			return;
		}
		if (status == Status.FAILED || next.ordinal() <= status.ordinal()) return;
		status = next;
	}

	public Long getId() { return id; }
	public Long getConversationId() { return conversationId; }
	public String getWaMessageId() { return waMessageId; }
	public Direction getDirection() { return direction; }
	public Type getType() { return type; }
	public String getBody() { return body; }
	public String getMediaId() { return mediaId; }
	public String getMediaMime() { return mediaMime; }
	public String getMediaFilename() { return mediaFilename; }
	public Status getStatus() { return status; }
	public String getError() { return error; }
	public Long getSentBy() { return sentBy; }
	public Instant getCreatedAt() { return createdAt; }
}
