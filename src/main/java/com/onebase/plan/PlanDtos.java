package com.onebase.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What crosses the wire for the Subscriptions tab. All prices are USD. */
public final class PlanDtos {

	private PlanDtos() {
	}

	/** One plan of the grid: its price (Subscriptions), and its cost and panel credit (Expenses). */
	public record PlanResponse(int devices, int months, BigDecimal price, BigDecimal cost, Integer credits, Instant updatedAt) {

		static PlanResponse from(Plan plan) {
			return new PlanResponse(plan.getDevices(), plan.getMonths(), plan.getPrice(), plan.getCost(), plan.getCredits(),
				plan.getUpdatedAt());
		}
	}

	/** One changed price. */
	public record PriceChange(
			@NotNull(message = "Say which plan (devices).") Integer devices,
			@NotNull(message = "Say which plan (months).") Integer months,
			@NotNull(message = "Enter a price.") BigDecimal price) {
	}

	/** One changed cost (Expenses): both numbers of the cell, together. */
	public record CostChange(
			@NotNull(message = "Say which plan (devices).") Integer devices,
			@NotNull(message = "Say which plan (months).") Integer months,
			@NotNull(message = "Enter a cost.") BigDecimal cost,
			@NotNull(message = "Enter the credits.") Integer credits) {
	}

	/** The Expenses Save button: every changed cost, saved together or not at all. */
	public record SaveCostsRequest(
			@NotEmpty(message = "Nothing to save.")
			@Size(max = 16, message = "There are only 16 plans.")
			List<@Valid CostChange> changes) {
	}

	/** The Save button: every price changed since the last save, saved together or not at all. */
	public record SavePricesRequest(
			@NotEmpty(message = "Nothing to save.")
			@Size(max = 16, message = "There are only 16 plans.")
			List<@Valid PriceChange> changes) {
	}
}
