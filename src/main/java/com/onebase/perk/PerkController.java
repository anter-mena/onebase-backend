package com.onebase.perk;

import com.onebase.perk.PerkService.PerkResponse;
import com.onebase.perk.PerkService.SavePerkRequest;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expenses → Perks. Read by both roles (the Add payment window, since 2026-10-06); changed by Admins only. No delete. */
@RestController
@RequestMapping("/api/perks")
@Tag(name = "Perks", description = "Read by both roles; changed by Admins only: extras that cost you money, such as IBO Player. No delete — switch off instead.")
public class PerkController {

	public record StatusRequest(@NotNull(message = "Say whether the perk is offered.") Boolean active) {
	}

	private final PerkService service;

	public PerkController(PerkService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Every perk, oldest first")
	public List<PerkResponse> list() {
		return service.list();
	}

	@PostMapping
	@Operation(summary = "Add a perk", description = "409 if another perk has the same name.")
	public ResponseEntity<PerkResponse> create(@AuthenticationPrincipal AuthPrincipal admin, @Valid @RequestBody SavePerkRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(admin, request));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Edit a perk", description = "Name, description and cost. Logged, saying what changed.")
	public PerkResponse update(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody SavePerkRequest request) {
		return service.update(admin, id, request);
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Offer a perk, or stop offering it")
	public PerkResponse setStatus(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody StatusRequest request) {
		return service.setActive(admin, id, request.active());
	}
}
