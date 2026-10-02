package com.onebase.user;

import com.onebase.security.AuthPrincipal;
import com.onebase.user.UserDtos.InviteRequest;
import com.onebase.user.UserDtos.InviteResponse;
import com.onebase.user.UserDtos.StatusRequest;
import com.onebase.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Users screen. Admins only — `SecurityConfig` refuses everyone else. */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Admins only: who is in the workspace, invitations, switching accounts on and off")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping
	@Operation(summary = "Everyone in the workspace except you, invited people included, oldest first")
	public List<UserResponse> list(@AuthenticationPrincipal AuthPrincipal admin) {
		return userService.list(admin);
	}

	@PostMapping("/invitations")
	@Operation(summary = "Invite people by email",
		description = "One role for all of them. If any email already has an account, nobody is invited (409).")
	public ResponseEntity<InviteResponse> invite(@AuthenticationPrincipal AuthPrincipal admin,
			@Valid @RequestBody InviteRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(userService.invite(admin, request));
	}

	@PostMapping("/{id}/invitation/resend")
	@Operation(summary = "Send the invitation again", description = "The previous link stops working.")
	public ResponseEntity<Void> resend(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id) {
		userService.resendInvitation(admin, id);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}/invitation")
	@Operation(summary = "Cancel an invitation", description = "Only for someone who has not joined yet.")
	public ResponseEntity<Void> cancel(@PathVariable long id) {
		userService.cancelInvitation(id);
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Switch an account on or off",
		description = "Switching off signs them out everywhere at once. Not allowed on yourself, or on any Admin.")
	public UserResponse setStatus(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody StatusRequest request) {
		return userService.setActive(admin, id, request.active());
	}
}
