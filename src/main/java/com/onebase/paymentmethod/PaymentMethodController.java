package com.onebase.paymentmethod;

import com.onebase.paymentmethod.PaymentMethodDtos.PaymentMethodResponse;
import com.onebase.paymentmethod.PaymentMethodDtos.SavePaymentMethodRequest;
import com.onebase.paymentmethod.PaymentMethodDtos.StatusRequest;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/**
 * Configuration → Payment methods. Changes: Admins only. The list: both roles
 * since 2026-10-06 — the Add payment window picks the account a payment was
 * made to (SecurityConfig).
 */
@RestController
@RequestMapping("/api/payment-methods")
@Tag(name = "Payment methods", description = "Admins only: the accounts you receive money on. No delete — switch off instead.")
public class PaymentMethodController {

	private final PaymentMethodService service;

	public PaymentMethodController(PaymentMethodService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Every payment method, oldest first")
	public List<PaymentMethodResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	@Operation(summary = "One payment method", description = "For the Edit page.")
	public PaymentMethodResponse get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	@Operation(summary = "Add a payment method", description = "409 if another method has the same name (capitals and extra spaces ignored).")
	public ResponseEntity<PaymentMethodResponse> create(@AuthenticationPrincipal AuthPrincipal admin,
			@Valid @RequestBody SavePaymentMethodRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(admin, request));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Edit a payment method", description = "Logged, saying what changed.")
	public PaymentMethodResponse update(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody SavePaymentMethodRequest request) {
		return service.update(admin, id, request);
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Switch a method on or off", description = "Off: it can't be chosen for new payments. Old payments keep it.")
	public PaymentMethodResponse setStatus(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody StatusRequest request) {
		return service.setActive(admin, id, request.active());
	}
}
