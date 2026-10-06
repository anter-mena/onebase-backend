package com.onebase.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A client (table {@code clients}). The plan, end date, orders, payment method and
 * revenue are not here: they come from the payments.
 *
 * <p>Two names: {@code fullName} is typed by the team, {@code username} is the
 * WhatsApp profile name and follows it. The screens show the full name, or the
 * username while there is none.
 */
@Entity
@Table(name = "clients")
public class Client {

	/** Where a client is, in funnel order. */
	public enum Status { NEW, CALLBACK, TRIAL, PENDING, ACTIVE, INACTIVE, DROP }

	/** Who made the row. Only WHATSAPP since 2026-10-06 (no Add client); MANUAL stays allowed by V12. */
	public enum Source { MANUAL, WHATSAPP }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "full_name")
	private String fullName;

	private String username;

	private String email;

	private String phone;

	private String country;

	@Column(name = "brand_id")
	private Long brandId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.NEW;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Source source;

	@Column(columnDefinition = "text")
	private String note;

	@Column(name = "created_by", updatable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Client() {
	}

	/** A number that wrote for the first time: New, no brand, named after its WhatsApp profile. */
	static Client fromWhatsApp(String phone, String country, String username) {
		Client client = new Client();
		client.source = Source.WHATSAPP;
		client.phone = phone;
		client.country = country;
		client.username = username;
		return client;
	}

	void update(String fullName, String email, String phone, String country, Long brandId, Status status) {
		this.fullName = fullName;
		this.email = email;
		this.phone = phone;
		this.country = country;
		this.brandId = brandId;
		this.status = status;
		touch();
	}

	void setNote(String note) {
		this.note = note;
		touch();
	}

	/** A message arrived: the WhatsApp name follows the profile, and a deleted client comes back. */
	boolean seenOnWhatsApp(String profileName) {
		boolean changed = false;
		if (profileName != null && !profileName.equals(username)) {
			username = profileName;
			changed = true;
		}
		if (deletedAt != null) {
			deletedAt = null;
			changed = true;
		}
		if (changed) touch();
		return changed;
	}

	/** A payment: the client is on its brand now, and Active. */
	public void paid(Long brandId) {
		this.brandId = brandId;
		this.status = Status.ACTIVE;
		touch();
	}

	/** Their paid time ran out. */
	public void lapsed() {
		this.status = Status.INACTIVE;
		touch();
	}

	void delete() {
		deletedAt = Instant.now();
		touch();
	}

	private void touch() {
		updatedAt = Instant.now();
	}

	/** What the screens call this client. */
	public String displayName() {
		if (fullName != null) return fullName;
		if (username != null) return username;
		return phone;
	}

	public Long getId() { return id; }
	public String getFullName() { return fullName; }
	public String getUsername() { return username; }
	public String getEmail() { return email; }
	public String getPhone() { return phone; }
	public String getCountry() { return country; }
	public Long getBrandId() { return brandId; }
	public Status getStatus() { return status; }
	public Source getSource() { return source; }
	public String getNote() { return note; }
	public Long getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public Instant getDeletedAt() { return deletedAt; }
	public boolean isDeleted() { return deletedAt != null; }
}
