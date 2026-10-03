package com.onebase.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** What crosses the wire for the Users screen and the invitation pages. */
public final class UserDtos {

	private UserDtos() {
	}

	/** One row of the Users table. */
	public record UserResponse(
			long id,
			String fullName,
			String email,
			UserRole role,
			boolean active,
			boolean invitePending,
			Instant lastActiveAt,
			Instant createdAt) {

		public static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
				user.isActive(), user.isInvitePending(), user.getLastActiveAt(), user.getCreatedAt());
		}
	}

	/** Several people at once, one role for all of them, and an optional note for the email. */
	public record InviteRequest(
			@NotEmpty(message = "Add at least one email.")
			@Size(max = 20, message = "Invite at most 20 people at a time.")
			List<@NotBlank(message = "An email is empty.") @Email(message = "One of the emails is not valid.") String> emails,
			@NotNull(message = "Choose a role.") UserRole role,
			@Size(max = 500, message = "Keep the message under 500 characters.") String message) {
	}

	public record InviteResponse(List<UserResponse> invited) {
	}

	public record StatusRequest(@NotNull(message = "Say whether the account is active.") Boolean active) {
	}

	// ── Public: the invitation link ──────────────────────────────────────

	/** The token travels in the body, not the URL, so it never lands in an access log. */
	public record InvitationTokenRequest(@NotBlank(message = "The invitation link is incomplete.") String token) {
	}

	/** What the "Accept invitation" page shows before the person fills it in, and when its link stops working. */
	public record InvitationInfo(String email, UserRole role, Instant expiresAt) {
	}

	public record AcceptInvitationRequest(
			@NotBlank(message = "The invitation link is incomplete.") String token,
			@NotBlank(message = "Enter your name.") @Size(max = 120, message = "Keep your name under 120 characters.") String fullName,
			@NotBlank(message = "Choose a password.")
			@Size(min = 8, max = 72, message = "Use between 8 and 72 characters.") String password) {
	}
}
