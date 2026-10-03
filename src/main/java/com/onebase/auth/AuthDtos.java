package com.onebase.auth;

import com.onebase.user.User;
import com.onebase.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Everything that crosses the wire for auth, in one place.
 *
 * <p>Passwords: 8 characters minimum, 72 maximum — the most BCrypt reads; a
 * longer one would be silently cut, so it is refused instead.
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	public record LoginRequest(
			@NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") String email,
			@NotBlank(message = "Enter your password.") String password) {

		/** A pasted email often carries spaces; drop them before validation sees it. */
		public LoginRequest {
			email = email == null ? null : email.trim();
		}
	}

	public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
	}

	public record ForgotPasswordRequest(
			@NotBlank(message = "Enter your email.") @Email(message = "Enter a valid email.") String email) {

		public ForgotPasswordRequest {
			email = email == null ? null : email.trim();
		}
	}

	public record ResetPasswordRequest(
			@NotBlank(message = "The reset link is incomplete.") String token,
			@NotBlank(message = "Enter a new password.")
			@Size(min = 8, max = 72, message = "Use between 8 and 72 characters.") String newPassword) {
	}

	public record ChangePasswordRequest(
			@NotBlank(message = "Enter your current password.") String currentPassword,
			@NotBlank(message = "Enter a new password.")
			@Size(min = 8, max = 72, message = "Use between 8 and 72 characters.") String newPassword) {
	}

	public record MessageResponse(String message) {
	}

	/** The reset link's token, in the body so it never lands in an access log. */
	public record ResetLinkRequest(@NotBlank(message = "The reset link is incomplete.") String token) {
	}

	/** When a reset link stops working — for the countdown on "Choose a new password". */
	public record ResetLinkInfo(java.time.Instant expiresAt) {
	}

	/** The signed-in user, as the sidebar and Account settings show them. */
	public record UserResponse(
			long id,
			String fullName,
			String email,
			UserRole role,
			String language,
			String timeZone,
			String dateFormat) {

		public static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
				user.getLanguage(), user.getTimeZone(), user.getDateFormat());
		}
	}
}
