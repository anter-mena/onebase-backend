package com.onebase.plan;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.common.ApiException;
import com.onebase.plan.PlanDtos.PlanResponse;
import com.onebase.plan.PlanDtos.PriceChange;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Subscriptions tab: the 16 plan prices, in USD.
 *
 * <p>Decided 2026-10-04: the grid is fixed (1–4 devices × 1, 3, 6, 12 months),
 * so there is no add and no delete — only prices change. A new price applies
 * to new payments only: each payment stores the price it was sold at.
 *
 * <p>Saving is <b>all or nothing</b>: one bad price and none of the changes are
 * kept, so the grid is never half-saved. Each change is a line in the Action
 * log ("2 devices · 12 months: $94.99 → $99.99") — the price history.
 */
@Service
public class PlanService {

	private static final Logger log = LoggerFactory.getLogger(PlanService.class);
	static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");

	private final PlanRepository plans;
	private final UserRepository users;
	private final ActionLogService actionLog;

	public PlanService(PlanRepository plans, UserRepository users, ActionLogService actionLog) {
		this.plans = plans;
		this.users = users;
		this.actionLog = actionLog;
	}

	@Transactional(readOnly = true)
	public List<PlanResponse> list() {
		return plans.findAllByOrderByDevicesAscMonthsAsc().stream().map(PlanResponse::from).toList();
	}

	@Transactional
	public List<PlanResponse> savePrices(AuthPrincipal admin, List<PriceChange> changes) {
		User actor = users.findById(admin.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
		Set<String> seen = new HashSet<>();

		for (PriceChange change : changes) {
			String label = label(change.devices(), change.months());
			if (!seen.add(label)) throw ApiException.badRequest(label + " is in the list twice.");
			BigDecimal price = validPrice(change.price(), label);
			Plan plan = plans.findByDevicesAndMonths(change.devices().shortValue(), change.months().shortValue())
				.orElseThrow(() -> ApiException.badRequest("There is no " + label + " plan."));
			if (plan.getPrice().compareTo(price) == 0) continue;

			String before = dollars(plan.getPrice());
			plan.setPrice(price);
			actionLog.record(actor, Action.UPDATED, TargetType.SUBSCRIPTION, plan.getId(), label,
				"Price changed from " + before + " to " + dollars(price));
		}
		log.info("User id={} saved {} plan price change(s)", admin.userId(), changes.size());
		return list();
	}

	/**
	 * The Expenses Save button: costs and credits, all or nothing, one log line
	 * per changed plan ("cost $10.00 → $11.00, credits 12 → 13").
	 */
	@Transactional
	public List<PlanResponse> saveCosts(AuthPrincipal admin, List<PlanDtos.CostChange> changes) {
		User actor = users.findById(admin.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
		Set<String> seen = new HashSet<>();

		for (PlanDtos.CostChange change : changes) {
			String label = label(change.devices(), change.months());
			if (!seen.add(label)) throw ApiException.badRequest(label + " is in the list twice.");
			BigDecimal cost = validCost(change.cost(), label);
			int credits = change.credits();
			if (credits < 0 || credits > 100_000) throw ApiException.badRequest("The credits of " + label + " must be between 0 and 100000.");
			Plan plan = plans.findByDevicesAndMonths(change.devices().shortValue(), change.months().shortValue())
				.orElseThrow(() -> ApiException.badRequest("There is no " + label + " plan."));

			java.util.List<String> parts = new java.util.ArrayList<>();
			if (plan.getCost() == null || plan.getCost().compareTo(cost) != 0) {
				parts.add("cost " + (plan.getCost() == null ? "none" : dollars(plan.getCost())) + " → " + dollars(cost));
			}
			if (plan.getCredits() == null || plan.getCredits() != credits) {
				parts.add("credits " + (plan.getCredits() == null ? "none" : plan.getCredits()) + " → " + credits);
			}
			if (parts.isEmpty()) continue;

			plan.setCost(cost, credits);
			actionLog.record(actor, Action.UPDATED, TargetType.SUBSCRIPTION, plan.getId(), label,
				"Changed " + String.join(", ", parts));
		}
		log.info("User id={} saved {} plan cost change(s)", admin.userId(), changes.size());
		return list();
	}

	/** 0 or more (a plan can cost nothing), at most two decimals — or 400 naming the plan. */
	private static BigDecimal validCost(BigDecimal cost, String label) {
		if (cost.signum() < 0) throw ApiException.badRequest("The cost of " + label + " can't be below $0.");
		if (cost.stripTrailingZeros().scale() > 2) {
			throw ApiException.badRequest("The cost of " + label + " can have at most 2 decimals.");
		}
		if (cost.compareTo(MAX_PRICE) > 0) throw ApiException.badRequest("The cost of " + label + " is too high.");
		return cost.setScale(2);
	}

	/** Above 0, at most two decimals, and a sane ceiling — or 400 naming the plan. */
	private static BigDecimal validPrice(BigDecimal price, String label) {
		if (price.signum() <= 0) throw ApiException.badRequest("The price of " + label + " must be above $0.");
		if (price.stripTrailingZeros().scale() > 2) {
			throw ApiException.badRequest("The price of " + label + " can have at most 2 decimals.");
		}
		if (price.compareTo(MAX_PRICE) > 0) throw ApiException.badRequest("The price of " + label + " is too high.");
		return price.setScale(2);
	}

	/** "2 devices · 12 months" */
	static String label(int devices, int months) {
		return devices + (devices == 1 ? " device" : " devices") + " · " + months + (months == 1 ? " month" : " months");
	}

	private static String dollars(BigDecimal amount) {
		return "$" + amount.setScale(2).toPlainString();
	}
}
