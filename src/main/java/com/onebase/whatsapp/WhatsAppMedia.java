package com.onebase.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Our own copy of a message's file (table {@code whatsapp_media}): Meta deletes its
 * copy after a while, this one stays and is backed up with the database.
 */
@Entity
@Table(name = "whatsapp_media")
public class WhatsAppMedia {

	@Id
	@Column(name = "message_id")
	private Long messageId;

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(nullable = false)
	private byte[] data;

	@Column(nullable = false)
	private String mime;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected WhatsAppMedia() {
	}

	WhatsAppMedia(long messageId, byte[] data, String mime) {
		this.messageId = messageId;
		this.data = data;
		this.mime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
		this.sizeBytes = data.length;
	}

	public Long getMessageId() { return messageId; }
	public byte[] getData() { return data; }
	public String getMime() { return mime; }
	public int getSizeBytes() { return sizeBytes; }
	public Instant getCreatedAt() { return createdAt; }
}
