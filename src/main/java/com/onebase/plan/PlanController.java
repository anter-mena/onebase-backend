package com.onebase.plan;

import com.onebase.plan.PlanDtos.PlanResponse;
import com.onebase.plan.PlanDtos.SavePricesRequest;
import com.onebase.security.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration → Subscriptions. Changing prices: Admins only. Reading: both
 * roles since 2026-10-06 — the Add payment window prices a payment from the
 * plans (SecurityConfig).
 */
@RestController
@RequestMapping("/api/plans")
@Tag(name = "Subscriptions", description = "Read by both roles; changed by Admins only: the 16 plan prices (1–4 devices × 1, 3, 6, 12 months), in USD")
public class PlanController {

	private final PlanService planService;

	public PlanController(PlanService planService) {
		this.planService = planService;
	}

	@GetMapping
	@Operation(summary = "The 16 plans and their prices (USD)", description = "Fewer devices first, then shorter first.")
	public List<PlanResponse> list() {
		return planService.list();
	}

	@PutMapping("/costs")
	@Operation(summary = "Save changed costs and credits (Expenses)",
		description = "All or nothing: one invalid value and none are saved (400). Each changed plan is written to the Action log.")
	public List<PlanResponse> saveCosts(@AuthenticationPrincipal AuthPrincipal admin,
			@Valid @RequestBody PlanDtos.SaveCostsRequest request) {
		return planService.saveCosts(admin, request.changes());
	}

	@PutMapping("/prices")
	@Operation(summary = "Save changed prices",
		description = "All or nothing: one invalid price and none are saved (400). Each change is written to the Action log. "
			+ "A new price applies to new payments only.")
	public List<PlanResponse> savePrices(@AuthenticationPrincipal AuthPrincipal admin,
			@Valid @RequestBody SavePricesRequest request) {
		return planService.savePrices(admin, request.changes());
	}
}
