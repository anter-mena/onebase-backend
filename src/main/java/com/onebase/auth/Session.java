package com.onebase.auth;

import com.onebase.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One sign-in (table {@code sessions}).
 *
 * <p>The access token names its session, and every request checks the row is
 * still open. That is the difference from the LMS, where a token stayed good
 * until it expired: here "Log out" and "Change password" end a session the
 * moment they are clicked, and a stolen token dies with it.
 */
@Entity
@Table(name = "sessions")
public class Session {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	private String ip;

	@Column(name = "user_agent")
	private String userAgent;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected Session() {
	}

	public Session(User user, String ip, String userAgent, Instant expiresAt) {
		this.id = UUID.randomUUID();
		this.user = user;
		this.ip = ip;
		this.userAgent = userAgent;
		this.expiresAt = expiresAt;
	}

	public boolean isOpen() {
		return revokedAt == null && expiresAt.isAfter(Instant.now());
	}

	public void revoke() {
		if (revokedAt == null) {
			revokedAt = Instant.now();
		}
	}

	public UUID getId() { return id; }
	public User getUser() { return user; }
	public Instant getExpiresAt() { return expiresAt; }
}
