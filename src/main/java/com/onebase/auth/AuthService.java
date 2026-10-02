package com.onebase.auth;

import com.onebase.auth.AuthDtos.ChangePasswordRequest;
import com.onebase.auth.AuthDtos.LoginRequest;
import com.onebase.auth.AuthDtos.LoginResponse;
import com.onebase.auth.AuthDtos.MessageResponse;
import com.onebase.auth.AuthDtos.ResetPasswordRequest;
import com.onebase.auth.AuthDtos.UserResponse;
import com.onebase.common.ApiException;
import com.onebase.mail.MailService;
import com.onebase.security.AuthPrincipal;
import com.onebase.security.JwtService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign in, sign out, and everything to do with passwords.
 *
 * <p>Follows the LMS where it was right: one generic "invalid email or
 * password" whatever went wrong, the same BCrypt work whether or not the email
 * exists (so timing does not reveal who has an account), and a lock after
 * repeated wrong passwords. No second factor, by decision.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	static final int MAX_FAILED_ATTEMPTS = 5;
	static final Duration LOCK_DURATION = Duration.ofMinutes(15);
	static final Duration RESET_LINK_TTL = Duration.ofMinutes(30);
	/** "Forgot password" pressed again within this window sends nothing new — no email flooding. */
	static final Duration RESET_RESEND_COOLDOWN = Duration.ofSeconds(60);

	private static final String INVALID_LOGIN = "Invalid email or password.";
	private static final String FORGOT_ANSWER =
		"If an account exists for this email, a reset link is on its way. Check your inbox.";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final DateTimeFormatter LOCK_TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final UserRepository users;
	private final SessionRepository sessions;
	private final AuthTokenRepository tokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final MailService mailService;
	private final String frontendUrl;
	/** Hashed once at startup; compared against when the email is unknown, to spend the same time. */
	private final String dummyHash;

	public AuthService(UserRepository users, SessionRepository sessions, AuthTokenRepository tokens,
			PasswordEncoder passwordEncoder, JwtService jwtService, MailService mailService,
			@Value("${onebase.frontend-url}") String frontendUrl) {
		this.users = users;
		this.sessions = sessions;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.mailService = mailService;
		this.frontendUrl = frontendUrl.replaceAll("/+$", "");
		this.dummyHash = passwordEncoder.encode("timing-equaliser-" + RANDOM.nextLong());
	}

	// ── Sign in / out ───────────────────────────────────────────────────────

	/**
	 * noRollbackFor: a wrong password must still count towards the lock, even
	 * though the method ends by throwing.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public LoginResponse login(LoginRequest request, String ip, String userAgent) {
		Optional<User> found = users.findByEmail(normalise(request.email()));
		if (found.isEmpty()) {
			passwordEncoder.matches(request.password(), dummyHash);
			throw ApiException.unauthorized(INVALID_LOGIN);
		}

		User user = found.get();
		if (user.isLocked()) {
			throw ApiException.tooManyRequests(
				"Too many failed attempts. Try again after " + LOCK_TIME.format(user.getLockedUntil()) + ".");
		}
		if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			registerFailedAttempt(user);
			throw ApiException.unauthorized(INVALID_LOGIN);
		}
		// Checked only after the password, so a stranger cannot learn an account is switched off.
		if (!user.canSignIn()) {
			throw ApiException.forbidden("This account is switched off. Ask the workspace owner.");
		}

		user.setFailedLoginAttempts(0);
		user.setLockedUntil(null);
		user.setLastActiveAt(Instant.now());

		Instant expiresAt = Instant.now().plus(jwtService.ttl());
		Session session = sessions.save(new Session(user, ip, truncate(userAgent, 255), expiresAt));
		String token = jwtService.issue(user.getId(), user.getRole().name(), session.getId(), expiresAt);
		log.info("User id={} signed in (session {})", user.getId(), session.getId());
		return new LoginResponse(token, "Bearer", jwtService.ttl().toSeconds(), UserResponse.from(user));
	}

	@Transactional
	public void logout(AuthPrincipal principal) {
		sessions.findById(principal.sessionId()).ifPresent(Session::revoke);
	}

	@Transactional(readOnly = true)
	public UserResponse me(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.map(UserResponse::from)
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}

	// ── Passwords ───────────────────────────────────────────────────────────

	/**
	 * Always answers the same sentence, whether or not the email has an account —
	 * otherwise this form becomes a way to find out who uses One Base. Pressing
	 * it again (the "Resend email" button) replaces the previous link.
	 */
	@Transactional
	public MessageResponse forgotPassword(String email) {
		users.findByEmail(normalise(email))
			.filter(User::canSignIn)
			.ifPresent(this::issueResetLink);
		return new MessageResponse(FORGOT_ANSWER);
	}

	@Transactional
	public MessageResponse resetPassword(ResetPasswordRequest request) {
		AuthToken token = tokens.findByTokenHashAndType(sha256(request.token()), AuthToken.Type.PASSWORD_RESET)
			.filter(AuthToken::isUsable)
			.orElseThrow(() -> ApiException.badRequest("This reset link has expired or was already used. Ask for a new one."));

		User user = token.getUser();
		if (!user.canSignIn()) {
			throw ApiException.badRequest("This reset link has expired or was already used. Ask for a new one.");
		}
		token.markUsed();
		user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
		user.setFailedLoginAttempts(0);
		user.setLockedUntil(null);
		// Whoever knew the old password is thrown out everywhere.
		sessions.revokeAll(user.getId(), Instant.now());
		log.info("User id={} reset their password", user.getId());
		return new MessageResponse("Your password has been changed. You can sign in now.");
	}

	@Transactional
	public MessageResponse changePassword(AuthPrincipal principal, ChangePasswordRequest request) {
		User user = users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
		if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
			throw ApiException.badRequest("Your current password is not correct.");
		}
		user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
		// Every other device is signed out; this one stays in.
		sessions.revokeAllExcept(user.getId(), principal.sessionId(), Instant.now());
		log.info("User id={} changed their password", user.getId());
		return new MessageResponse("Your password has been changed. Other devices have been signed out.");
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private void issueResetLink(User user) {
		var open = tokens.findByUserIdAndTypeAndUsedAtIsNull(user.getId(), AuthToken.Type.PASSWORD_RESET);
		Instant now = Instant.now();
		boolean justSent = open.stream().anyMatch(t -> t.getCreatedAt().isAfter(now.minus(RESET_RESEND_COOLDOWN)));
		if (justSent) {
			return;
		}
		open.forEach(AuthToken::markUsed); // the previous link stops working

		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		tokens.save(new AuthToken(user, AuthToken.Type.PASSWORD_RESET, sha256(token), now.plus(RESET_LINK_TTL)));
		mailService.sendPasswordReset(user.getEmail(), user.getFullName(), frontendUrl + "/reset-password?token=" + token);
	}

	private void registerFailedAttempt(User user) {
		int attempts = user.getFailedLoginAttempts() + 1;
		user.setFailedLoginAttempts(attempts);
		if (attempts >= MAX_FAILED_ATTEMPTS) {
			user.setLockedUntil(Instant.now().plus(LOCK_DURATION));
			user.setFailedLoginAttempts(0);
			log.warn("User id={} locked for {} after {} wrong passwords", user.getId(), LOCK_DURATION, attempts);
		}
	}

	static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String normalise(String email) {
		return email == null ? "" : email.trim().toLowerCase();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
