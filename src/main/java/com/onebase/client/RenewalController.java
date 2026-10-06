package com.onebase.client;

import com.onebase.client.ClientDtos.RenewalRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Renewals: the clients to follow up. Both roles (SecurityConfig.COMMERCIAL_API). */
@RestController
@Tag(name = "Renewals", description = "Who to follow up: ending in 10 days or less, trials over (Callback), Pending, Inactive.")
public class RenewalController {

	private final ClientService clients;

	public RenewalController(ClientService clients) {
		this.clients = clients;
	}

	@GetMapping("/api/renewals")
	@Operation(summary = "The clients to follow up", description = "Each with its group and the days left on its plan (negative once ended), soonest first.")
	public List<RenewalRow> renewals() {
		return clients.renewals();
	}
}
