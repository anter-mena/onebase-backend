package com.onebase.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The System status screen. Admins only — `/api/system/**` is not in
 * `SecurityConfig.COMMERCIAL_API`, so everyone else gets 403.
 *
 * <p>Ported from the LMS's System health, which was built and verified against
 * the same kind of server.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Admins only: the server, the containers, the backend and the database")
public class SystemController {

	private final SystemMetricsService systemMetrics;

	public SystemController(SystemMetricsService systemMetrics) {
		this.systemMetrics = systemMetrics;
	}

	@GetMapping("/health")
	@Operation(summary = "Live figures for the server, containers, backend and database",
		description = """
			One call rather than four, so the panels describe the same moment.

			Host CPU, memory, load and disk come from /proc, which Docker does not put in a namespace — \
			the machine's real figures. Network reads the host's counters through a mount; \
			server.networkIsHost says whether it is there.

			Containers come from a read-only Docker proxy, never the socket. containers.available is false \
			when no proxy answers (always, locally), and the list is then empty.

			Network bytes and request totals are cumulative; the page turns them into rates by polling.""")
	public SystemHealthResponse health() {
		return systemMetrics.snapshot();
	}
}
