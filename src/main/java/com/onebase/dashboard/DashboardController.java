package com.onebase.dashboard;

import com.onebase.dashboard.DashboardService.Overview;
import com.onebase.dashboard.DashboardService.Target;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Dashboard. Admins only (not in SecurityConfig.COMMERCIAL_API). */
@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Admins only: revenue, costs, clients, panel credit and accounts for a period.")
public class DashboardController {

	public record TargetRequest(@NotNull(message = "Enter the target.") BigDecimal target) {
	}

	private final DashboardService service;

	public DashboardController(DashboardService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Everything the Dashboard draws", description = "range: today, yesterday, 7d, month, year, custom (with from and to).")
	public Overview overview(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		return service.overview(range, from, to);
	}

	@PutMapping("/target")
	@Operation(summary = "Set this month's target", description = "USD. Logged.")
	public Target target(@AuthenticationPrincipal AuthPrincipal admin, @RequestBody @jakarta.validation.Valid TargetRequest request) {
		return service.setTarget(admin, request.target());
	}
}
