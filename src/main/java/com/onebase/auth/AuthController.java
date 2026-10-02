package com.onebase.auth;

import com.onebase.auth.AuthDtos.ChangePasswordRequest;
import com.onebase.auth.AuthDtos.ForgotPasswordRequest;
import com.onebase.auth.AuthDtos.LoginRequest;
import com.onebase.auth.AuthDtos.LoginResponse;
import com.onebase.auth.AuthDtos.MessageResponse;
import com.onebase.auth.AuthDtos.ResetPasswordRequest;
import com.onebase.auth.AuthDtos.UserResponse;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in, sign out, and passwords")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Sign in with email and password",
		description = "Returns an access token to send as `Authorization: Bearer …`. "
			+ "5 wrong passwords lock the account for 15 minutes (429).")
	public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return authService.login(request, clientIp(http), http.getHeader(HttpHeaders.USER_AGENT));
	}

	@PostMapping("/logout")
	@Operation(summary = "Sign out", description = "Ends this session at once; the token stops working.")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
		authService.logout(principal);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	@Operation(summary = "The signed-in user", description = "Name, email and role — what the sidebar shows.")
	public UserResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
		return authService.me(principal);
	}

	@PostMapping("/password/forgot")
	@SecurityRequirements
	@Operation(summary = "Email a password-reset link",
		description = "Always answers the same, whether or not the email has an account. "
			+ "Calling it again (\"Resend email\") replaces the previous link.")
	public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		return authService.forgotPassword(request.email());
	}

	@PostMapping("/password/reset")
	@SecurityRequirements
	@Operation(summary = "Set a new password from the emailed link",
		description = "Signs the account out everywhere.")
	public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		return authService.resetPassword(request);
	}

	@PostMapping("/password/change")
	@Operation(summary = "Change your password",
		description = "Needs the current password. Other devices are signed out; this one stays in.")
	public MessageResponse changePassword(@AuthenticationPrincipal AuthPrincipal principal,
			@Valid @RequestBody ChangePasswordRequest request) {
		return authService.changePassword(principal, request);
	}

	/**
	 * The caller's address. Requests arrive through the Next.js proxy (and later
	 * Caddy), so the first {@code X-Forwarded-For} entry is the real one. Stored
	 * for the sessions list only — never used to decide access.
	 */
	private static String clientIp(HttpServletRequest http) {
		String forwarded = http.getHeader("X-Forwarded-For");
		String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
		return ip.length() > 45 ? ip.substring(0, 45) : ip;
	}
}
