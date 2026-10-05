package com.onebase.client;

import com.onebase.client.ClientDtos.ClientResponse;
import com.onebase.client.ClientDtos.NoteRequest;
import com.onebase.client.ClientDtos.SaveClientRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clients. Admins and Commercials (SecurityConfig.COMMERCIAL_API); delete is
 * Admin-only (SecurityConfig).
 */
@RestController
@RequestMapping("/api/clients")
@Tag(name = "Clients", description = "Your clients. A WhatsApp number that writes for the first time becomes a New client by itself.")
public class ClientController {

	private final ClientService service;

	public ClientController(ClientService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Every client, newest first", description = "Deleted clients are left out.")
	public List<ClientResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	@Operation(summary = "One client")
	public ClientResponse get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	@Operation(summary = "Add a client", description = "Full name, and a phone or an email. 409 if the phone already belongs to a client.")
	public ResponseEntity<ClientResponse> create(@AuthenticationPrincipal AuthPrincipal user,
			@Valid @RequestBody SaveClientRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, request));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Edit a client", description = "The left card of the client page. Logged, saying what changed.")
	public ClientResponse update(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@Valid @RequestBody SaveClientRequest request) {
		return service.update(user, id, request);
	}

	@PutMapping("/{id}/note")
	@Operation(summary = "Save the note", description = "Empty removes it.")
	public ClientResponse note(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@Valid @RequestBody NoteRequest request) {
		return service.setNote(user, id, request.note());
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a client (Admins)", description = "Soft: their past payments still count. They come back if they write on WhatsApp again.")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id) {
		service.delete(user, id);
		return ResponseEntity.noContent().build();
	}
}
