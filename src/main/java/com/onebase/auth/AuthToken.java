package com.onebase.auth;

import com.onebase.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A one-time link (table {@code auth_tokens}): a password reset today, an
 * invitation once the Users screen is wired.
 *
 * <p>Holds a SHA-256 of the token, never the token itself — the only copy of
 * the real value is the one in the email.
 */
@Entity
@Table(name = "auth_tokens")
public class AuthToken {

	public enum Type { PASSWORD_RESET, INVITE }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected AuthToken() {
	}

	public AuthToken(User user, Type type, String tokenHash, Instant expiresAt) {
		this.user = user;
		this.type = type;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}

	/** Not used yet, and not past its expiry. */
	public boolean isUsable() {
		return usedAt == null && expiresAt.isAfter(Instant.now());
	}

	public User getUser() { return user; }
	public Type getType() { return type; }
	public Instant getCreatedAt() { return createdAt; }
	public void markUsed() { this.usedAt = Instant.now(); }
}
