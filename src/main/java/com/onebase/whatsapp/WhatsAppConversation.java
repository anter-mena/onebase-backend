package com.onebase.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One WhatsApp number we talk with. */
@Entity
@Table(name = "whatsapp_conversations")
public class WhatsAppConversation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "wa_id", nullable = false, unique = true)
	private String waId;

	@Column(name = "contact_name")
	private String contactName;

	@Column(name = "unread_count", nullable = false)
	private int unreadCount;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@Column(name = "last_preview")
	private String lastPreview;

	@Column(name = "last_inbound_at")
	private Instant lastInboundAt;

	@Column(name = "client_id")
	private Long clientId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected WhatsAppConversation() {
	}

	WhatsAppConversation(String waId, String contactName) {
		this.waId = waId;
		this.contactName = contactName;
	}

	void received(String name, String preview, Instant at) {
		if (name != null && !name.isBlank()) contactName = name;
		unreadCount++;
		if (lastInboundAt == null || at.isAfter(lastInboundAt)) lastInboundAt = at;
		touch(preview, at);
	}

	void sent(String preview, Instant at) {
		touch(preview, at);
	}

	void markRead() {
		unreadCount = 0;
	}

	/** The client this number belongs to (the Clients module keeps it right). */
	public void linkClient(Long clientId) {
		this.clientId = clientId;
	}

	private void touch(String preview, Instant at) {
		if (lastMessageAt != null && at.isBefore(lastMessageAt)) return;
		lastMessageAt = at;
		lastPreview = preview == null ? null : preview.length() > 200 ? preview.substring(0, 200) : preview;
	}

	public Long getId() { return id; }
	public String getWaId() { return waId; }
	public String getContactName() { return contactName; }
	public int getUnreadCount() { return unreadCount; }
	public Instant getLastMessageAt() { return lastMessageAt; }
	public String getLastPreview() { return lastPreview; }
	public Instant getLastInboundAt() { return lastInboundAt; }
	public Long getClientId() { return clientId; }
	public Instant getCreatedAt() { return createdAt; }
}
