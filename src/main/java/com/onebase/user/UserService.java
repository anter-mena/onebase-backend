package com.onebase.user;

import com.onebase.auth.AuthToken;
import com.onebase.auth.AuthTokenRepository;
import com.onebase.auth.OneTimeTokens;
import com.onebase.auth.SessionRepository;
import com.onebase.common.ApiException;
import com.onebase.mail.MailService;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.UserDtos.AcceptInvitationRequest;
import com.onebase.user.UserDtos.InvitationInfo;
import com.onebase.user.UserDtos.InviteRequest;
import com.onebase.user.UserDtos.InviteResponse;
import com.onebase.user.UserDtos.UserResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Users module: who is in the workspace, inviting people, and switching
 * accounts on and off.
 *
 * <p>What it deliberately does <b>not</b> do (decided 2026-10-02): edit a user,
 * change a role, or delete anyone who has joined. People are switched off
 * instead, so their name stays in the history. The one removal is cancelling an
 * invitation nobody accepted — a mistyped email — where there is nothing to lose.
 *
 * <p>Two rules protect the workspace from locking itself out (decided 2026-10-02):
 * nobody can switch off their own account, and <b>no Admin can ever be switched
 * off</b> — by anyone. So the workspace can never be left without an Admin.
 *
 * <p>The list leaves out the Admin who asks for it: the Users screen is for
 * managing the others.
 */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	static final Duration INVITE_TTL = Duration.ofDays(7);
	/** "Resend invite" pressed again within this window sends nothing new — no email flooding. */
	static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

	private static final String INVITE_GONE = "This invitation has expired or was already used. Ask an Admin to send a new one.";

	private final UserRepository users;
	private final AuthTokenRepository tokens;
	private final SessionRepository sessions;
	private final PasswordEncoder passwordEncoder;
	private final MailService mailService;
	private final String frontendUrl;

	public UserService(UserRepository users, AuthTokenRepository tokens, SessionRepository sessions,
			PasswordEncoder passwordEncoder, MailService mailService,
			@Value("${onebase.frontend-url}") String frontendUrl) {
		this.users = users;
		this.tokens = tokens;
		this.sessions = sessions;
		this.passwordEncoder = passwordEncoder;
		this.mailService = mailService;
		this.frontendUrl = frontendUrl.replaceAll("/+$", "");
	}

	// ── Admin: the Users screen ─────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<UserResponse> list(AuthPrincipal admin) {
		return users.findAll(Sort.by("createdAt")).stream()
			.filter(user -> user.getId() != admin.userId())
			.map(UserResponse::from)
			.toList();
	}

	/**
	 * Invites everyone on the list, or no one: if any address already has an
	 * account, nothing is sent and the answer names the ones to remove.
	 */
	@Transactional
	public InviteResponse invite(AuthPrincipal admin, InviteRequest request) {
		Set<String> emails = new LinkedHashSet<>();
		request.emails().forEach(email -> emails.add(email.trim().toLowerCase()));

		List<String> taken = users.findByEmailIn(emails).stream().map(User::getEmail).toList();
		if (!taken.isEmpty()) {
			throw ApiException.conflict("Already in this workspace: " + String.join(", ", taken) + ". Remove them and send again.");
		}

		String invitedBy = nameOf(admin);
		List<UserResponse> invited = emails.stream().map(email -> {
			User user = users.save(User.invited(email, request.role()));
			sendInvitation(user, invitedBy, request.message());
			return UserResponse.from(user);
		}).toList();

		log.info("User id={} invited {} people as {}", admin.userId(), invited.size(), request.role());
		return new InviteResponse(invited);
	}

	/** A fresh link; the previous one stops working. */
	@Transactional
	public void resendInvitation(AuthPrincipal admin, long userId) {
		User user = pendingInvite(userId);
		var open = tokens.findByUserIdAndTypeAndUsedAtIsNull(user.getId(), AuthToken.Type.INVITE);
		Instant now = Instant.now();
		if (open.stream().anyMatch(t -> t.getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN)))) {
			throw ApiException.tooManyRequests("An invitation was just sent. Wait a minute before sending another one.");
		}
		open.forEach(AuthToken::markUsed);
		sendInvitation(user, nameOf(admin), null);
	}

	/** Only for someone who never joined — a mistyped email, a change of mind. */
	@Transactional
	public void cancelInvitation(long userId) {
		User user = pendingInvite(userId);
		users.delete(user); // its links go with it (ON DELETE CASCADE)
		log.info("Invitation for user id={} cancelled", userId);
	}

	@Transactional
	public UserResponse setActive(AuthPrincipal admin, long userId, boolean active) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("This user does not exist."));

		if (user.getId() == admin.userId()) {
			throw ApiException.badRequest("You can't switch off your own account.");
		}
		if (user.isInvitePending()) {
			throw ApiException.badRequest("This person hasn't joined yet. Cancel the invitation instead.");
		}
		if (!active && user.getRole() == UserRole.ADMIN) {
			throw ApiException.badRequest("Admins can't be switched off.");
		}

		user.setActive(active);
		if (!active) {
			// Signed out everywhere, now — not when their token runs out.
			sessions.revokeAll(user.getId(), Instant.now());
		}
		log.info("User id={} {} user id={}", admin.userId(), active ? "activated" : "deactivated", userId);
		return UserResponse.from(user);
	}

	// ── Public: the invitation link ─────────────────────────────────────────

	/** What the "Accept invitation" page shows: who the link is for. */
	@Transactional(readOnly = true)
	public InvitationInfo invitationInfo(String token) {
		User user = usableInvitation(token).getUser();
		return new InvitationInfo(user.getEmail(), user.getRole());
	}

	@Transactional
	public void acceptInvitation(AcceptInvitationRequest request) {
		AuthToken token = usableInvitation(request.token());
		token.markUsed();
		token.getUser().acceptInvitation(request.fullName().trim(), passwordEncoder.encode(request.password()));
		log.info("User id={} accepted their invitation", token.getUser().getId());
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private void sendInvitation(User user, String invitedBy, String message) {
		String token = OneTimeTokens.generate();
		tokens.save(new AuthToken(user, AuthToken.Type.INVITE, OneTimeTokens.hash(token), Instant.now().plus(INVITE_TTL)));
		String roleLabel = user.getRole() == UserRole.ADMIN ? "an Admin" : "a Commercial";
		mailService.sendInvitation(user.getEmail(), invitedBy, roleLabel, message, frontendUrl + "/accept-invite?token=" + token);
	}

	private User pendingInvite(long userId) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("This user does not exist."));
		if (!user.isInvitePending()) {
			throw ApiException.badRequest("This person has already joined. Switch their account off instead.");
		}
		return user;
	}

	private AuthToken usableInvitation(String token) {
		return tokens.findByTokenHashAndType(OneTimeTokens.hash(token), AuthToken.Type.INVITE)
			.filter(AuthToken::isUsable)
			.filter(t -> t.getUser().isInvitePending())
			.orElseThrow(() -> ApiException.badRequest(INVITE_GONE));
	}

	private String nameOf(AuthPrincipal principal) {
		return users.findById(principal.userId()).map(User::getFullName).orElse("An Admin");
	}
}
