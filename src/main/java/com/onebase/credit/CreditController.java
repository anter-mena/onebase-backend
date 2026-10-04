package com.onebase.credit;

import com.onebase.credit.CreditService.CreditSummary;
import com.onebase.credit.CreditService.TopupRequest;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expenses → Panel credit. Admins only (not in `SecurityConfig.COMMERCIAL_API`). */
@RestController
@RequestMapping("/api/credit")
@Tag(name = "Panel credit", description = "Admins only: credit bought (top-ups) and credit left")
public class CreditController {

	private final CreditService service;

	public CreditController(CreditService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Credit left, total bought and paid, and the last top-up")
	public CreditSummary summary() {
		return service.summary();
	}

	@PostMapping("/topups")
	@Operation(summary = "Top up: credits bought and what they cost (USD)", description = "Never edited or deleted. Logged.")
	public ResponseEntity<CreditSummary> topUp(@AuthenticationPrincipal AuthPrincipal admin, @Valid @RequestBody TopupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.topUp(admin, request));
	}
}
