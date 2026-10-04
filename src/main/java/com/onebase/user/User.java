package com.onebase.user;

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
 * Someone who can sign in to the workspace (table {@code users}).
 *
 * <p>Only the fields auth needs have behaviour here; the display preferences
 * (time zone, date format, notifications) are plain columns the Account settings
 * screen will read and write later.
 */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserRole role;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "invite_pending", nullable = false)
	private boolean invitePending;

	@Column(name = "time_zone", nullable = false)
	private String timeZone = "UTC";

	@Column(name = "date_format", nullable = false)
	private String dateFormat = "dd MMM yyyy";

	@Column(name = "notify_renewals", nullable = false)
	private boolean notifyRenewals = true;

	@Column(name = "notify_failed_payments", nullable = false)
	private boolean notifyFailedPayments = true;

	@Column(name = "notify_weekly_digest", nullable = false)
	private boolean notifyWeeklyDigest;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_active_at")
	private Instant lastActiveAt;

	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected User() {
	}

	public User(String fullName, String email, String passwordHash, UserRole role) {
		this.fullName = fullName;
		this.email = email;
		this.passwordHash = passwordHash;
		this.passwordChangedAt = passwordHash == null ? null : Instant.now();
		this.role = role;
	}

	/**
	 * Someone invited but not arrived yet: no password, can't sign in.
	 *
	 * <p>They choose their own name when they accept, so until then the name is
	 * the part of the email before the "@" — enough for the Users list to show
	 * something readable.
	 */
	public static User invited(String email, UserRole role) {
		User user = new User(email.substring(0, email.indexOf('@')), email, null, role);
		user.invitePending = true;
		return user;
	}

	/** The invited person has chosen their name and password: they are in. */
	public void acceptInvitation(String fullName, String passwordHash) {
		this.fullName = fullName;
		setPasswordHash(passwordHash);
		this.invitePending = false;
		this.active = true;
	}

	/** Locked out by too many wrong passwords, and the lock has not run out yet. */
	public boolean isLocked() {
		return lockedUntil != null && lockedUntil.isAfter(Instant.now());
	}

	/** Switched on, and has finished accepting their invitation (so has a password). */
	public boolean canSignIn() {
		return active && !invitePending && passwordHash != null;
	}

	public Long getId() { return id; }
	public String getFullName() { return fullName; }
	public String getEmail() { return email; }
	public String getPasswordHash() { return passwordHash; }
	/** Also stamps when, for "Last changed …" in Account settings. */
	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		this.passwordChangedAt = Instant.now();
	}
	public Instant getPasswordChangedAt() { return passwordChangedAt; }
	public UserRole getRole() { return role; }
	public boolean isActive() { return active; }
	public void setActive(boolean active) { this.active = active; }
	public boolean isInvitePending() { return invitePending; }
	public String getTimeZone() { return timeZone; }
	public String getDateFormat() { return dateFormat; }
	public boolean isNotifyRenewals() { return notifyRenewals; }
	public boolean isNotifyFailedPayments() { return notifyFailedPayments; }
	public boolean isNotifyWeeklyDigest() { return notifyWeeklyDigest; }

	/** Account settings → General. The email and role are not here: nobody changes their own. */
	public void updateSettings(String fullName, String timeZone, String dateFormat,
			boolean notifyRenewals, boolean notifyFailedPayments, boolean notifyWeeklyDigest) {
		this.fullName = fullName;
		this.timeZone = timeZone;
		this.dateFormat = dateFormat;
		this.notifyRenewals = notifyRenewals;
		this.notifyFailedPayments = notifyFailedPayments;
		this.notifyWeeklyDigest = notifyWeeklyDigest;
	}
	public int getFailedLoginAttempts() { return failedLoginAttempts; }
	public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
	public Instant getLockedUntil() { return lockedUntil; }
	public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
	public Instant getLastActiveAt() { return lastActiveAt; }
	public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
	public Instant getCreatedAt() { return createdAt; }
}
