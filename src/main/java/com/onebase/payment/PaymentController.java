package com.onebase.payment;

import com.onebase.payment.PaymentDtos.CreatePaymentRequest;
import com.onebase.payment.PaymentDtos.CreatedPayment;
import com.onebase.payment.PaymentDtos.PaymentResponse;
import com.onebase.payment.PaymentDtos.ProofFile;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

	@PostMapping(path = "/api/clients/{clientId}/payments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Add a payment with its proof", description = "The proof is required (2026-10-28). Spends the plan's panel credit, puts the client on the brand and makes them Active; "
		+ "a renewal starts where the running term ends; `warning` is set when the panel credit goes below zero. "
		+ "Multipart: `payment` (JSON) and `proofs` (1 to 5 files, 10 MB together): "
		+ "PDF, JPG, PNG, WebP, HEIC, GIF, TIFF, BMP, DOC(X), XLS(X), ODT, ODS, RTF, CSV, TXT, XML, EML or MSG. Saved with the payment or not at all.")
	public ResponseEntity<CreatedPayment> createWithProof(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long clientId,
			@Valid @RequestPart("payment") CreatePaymentRequest request,
			@RequestPart(name = "proofs", required = false) List<MultipartFile> proofs) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.createWithProof(user, clientId, request, proofs));
	}

	@GetMapping("/api/clients/{clientId}/payments/{paymentId}/proofs/{proofId}")
	@Operation(summary = "A payment's proof file", description = "Served as exactly what it is (its type was read from the bytes), never run as a page.")
	public ResponseEntity<byte[]> proof(@PathVariable long clientId, @PathVariable long paymentId, @PathVariable long proofId) {
		ProofFile file = service.proof(clientId, paymentId, proofId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.mime()))
			.cacheControl(CacheControl.noCache().cachePrivate())
			.header(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'; sandbox")
			.body(file.bytes());
	}

	@GetMapping("/api/ledger")
	@Operation(summary = "The Ledger (Admins)", description = "Every payment received in the period, newest first, and the panel-credit top-ups paid in it (money out; none when filtered to one account). "
		+ "range: today, yesterday, week, month, year, custom (with from and to). methodId: only one account.")
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
