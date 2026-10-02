package com.onebase.user;

import com.onebase.user.UserDtos.AcceptInvitationRequest;
import com.onebase.user.UserDtos.InvitationInfo;
import com.onebase.user.UserDtos.InvitationTokenRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitation link — public, because the person opening it has no account yet.
 * The link's token is the only thing that lets them in, and it works once.
 */
@RestController
@RequestMapping("/api/invitations")
@Tag(name = "Invitations", description = "Public: opening and accepting an invitation link")
public class InvitationController {

	private final UserService userService;

	public InvitationController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping("/check")
	@SecurityRequirements
	@Operation(summary = "Who an invitation link is for", description = "400 if it expired, was used, or was cancelled.")
	public InvitationInfo check(@Valid @RequestBody InvitationTokenRequest request) {
		return userService.invitationInfo(request.token());
	}

	@PostMapping("/accept")
	@SecurityRequirements
	@Operation(summary = "Accept an invitation", description = "Sets the person's name and password. They can sign in right after.")
	public ResponseEntity<Void> accept(@Valid @RequestBody AcceptInvitationRequest request) {
		userService.acceptInvitation(request);
		return ResponseEntity.noContent().build();
	}
}
