package com.onebase.payment;

import com.onebase.payment.PaymentDtos.CreatePaymentRequest;
import com.onebase.payment.PaymentDtos.CreatedPayment;
import com.onebase.payment.PaymentDtos.PaymentResponse;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A client's payments. Listing and adding live under {@code /api/clients/…}, so
 * both roles reach them (SecurityConfig.COMMERCIAL_API); deleting is
 * {@code /api/payments/{id}}, which falls to Admin-only.
 */
@RestController
@Tag(name = "Payments", description = "A client's payments. Price, cost and panel credit come from Configuration; the amount can be a private price.")
public class PaymentController {

	private final PaymentService service;

	public PaymentController(PaymentService service) {
		this.service = service;
	}

	@GetMapping("/api/clients/{clientId}/payments")
	@Operation(summary = "A client's payments, newest first")
	public List<PaymentResponse> list(@PathVariable long clientId) {
		return service.list(clientId);
	}

	@PostMapping("/api/clients/{clientId}/payments")
	@Operation(summary = "Add a payment", description = "Spends the plan's panel credit, puts the client on the brand and makes them Active. "
		+ "A renewal starts where the running term ends. `warning` is set when the panel credit goes below zero.")
	public ResponseEntity<CreatedPayment> create(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long clientId,
			@Valid @RequestBody CreatePaymentRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, clientId, request));
	}

	@GetMapping("/api/ledger")
	@Operation(summary = "The Ledger (Admins)", description = "Every payment received in the period, newest first. "
		+ "range: today, yesterday, 7d, month, year, custom (with from and to). methodId: only one account.")
	public PaymentDtos.Ledger ledger(@RequestParam(required = false) Long methodId, @RequestParam(required = false) String range,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
		return service.ledger(methodId, range, from, to);
	}

	@DeleteMapping("/api/payments/{id}")
	@Operation(summary = "Delete a payment (Admins)", description = "Soft and logged: it leaves every total and its credits come back.")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id) {
		service.delete(user, id);
		return ResponseEntity.noContent().build();
	}
}
