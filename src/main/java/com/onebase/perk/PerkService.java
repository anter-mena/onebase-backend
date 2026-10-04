package com.onebase.perk;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.common.ApiException;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expenses → Perks: create, edit, switch on and off. Admins only. No delete
 * (decided 2026-10-04): a perk is switched off instead. Only the cost is
 * stored for now; how it adds up on a payment is decided with Payments.
 */
@Service
public class PerkService {

	static final BigDecimal MAX_COST = new BigDecimal("99999.99");

	/** One perk, as the Expenses tab shows it. Cost in USD. */
	public record PerkResponse(long id, String name, String description, BigDecimal cost, boolean active, Instant createdAt) {
		static PerkResponse from(Perk perk) {
			return new PerkResponse(perk.getId(), perk.getName(), perk.getDescription(), perk.getCost(), perk.isActive(),
				perk.getCreatedAt());
		}
	}

	/** Create and Edit send the whole perk. */
	public record SavePerkRequest(
			@NotBlank(message = "Enter the perk's name.") @Size(max = 100, message = "Keep the name under 100 characters.") String name,
			@Size(max = 255, message = "Keep the description under 255 characters.") String description,
			@NotNull(message = "Enter the cost.") BigDecimal cost) {

		public SavePerkRequest {
			name = name == null ? null : name.trim().replaceAll("\\s+", " ");
			description = description == null || description.isBlank() ? null : description.trim();
		}
	}

	private final PerkRepository perks;
	private final UserRepository users;
	private final ActionLogService actionLog;

	public PerkService(PerkRepository perks, UserRepository users, ActionLogService actionLog) {
		this.perks = perks;
		this.users = users;
		this.actionLog = actionLog;
	}

	@Transactional(readOnly = true)
	public List<PerkResponse> list() {
		return perks.findAllByOrderByCreatedAtAscIdAsc().stream().map(PerkResponse::from).toList();
	}

	@Transactional
	public PerkResponse create(AuthPrincipal admin, SavePerkRequest request) {
		BigDecimal cost = validCost(request.cost());
		refuseTaken(request.name(), null);
		Perk perk = save(new Perk(request.name(), request.description(), cost, true));
		actionLog.record(actor(admin), Action.CREATED, TargetType.PERK, perk.getId(), perk.getName(),
			"Added a perk costing " + dollars(cost));
		return PerkResponse.from(perk);
	}

	@Transactional
	public PerkResponse update(AuthPrincipal admin, long id, SavePerkRequest request) {
		Perk perk = find(id);
		BigDecimal cost = validCost(request.cost());
		refuseTaken(request.name(), perk.getId());

		List<String> changed = new ArrayList<>();
		if (!perk.getName().equals(request.name())) changed.add("name from " + perk.getName() + " to " + request.name());
		if (!Objects.equals(perk.getDescription(), request.description())) changed.add("description");
		if (perk.getCost().compareTo(cost) != 0) changed.add("cost from " + dollars(perk.getCost()) + " to " + dollars(cost));

		perk.update(request.name(), request.description(), cost);
		save(perk);
		if (!changed.isEmpty()) {
			actionLog.record(actor(admin), Action.UPDATED, TargetType.PERK, perk.getId(), perk.getName(),
				"Changed " + String.join(", ", changed));
		}
		return PerkResponse.from(perk);
	}

	@Transactional
	public PerkResponse setActive(AuthPrincipal admin, long id, boolean active) {
		Perk perk = find(id);
		if (perk.isActive() != active) {
			perk.setActive(active);
			actionLog.record(actor(admin), active ? Action.ACTIVATED : Action.DEACTIVATED, TargetType.PERK,
				perk.getId(), perk.getName(),
				active ? "Perk offered again" : "Perk no longer offered; nothing was deleted");
		}
		return PerkResponse.from(perk);
	}

	private static BigDecimal validCost(BigDecimal cost) {
		if (cost.signum() < 0) throw ApiException.badRequest("The cost can't be below $0.");
		if (cost.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("The cost can have at most 2 decimals.");
		if (cost.compareTo(MAX_COST) > 0) throw ApiException.badRequest("The cost is too high.");
		return cost.setScale(2);
	}

	private Perk find(long id) {
		return perks.findById(id).orElseThrow(() -> ApiException.notFound("This perk does not exist."));
	}

	private void refuseTaken(String name, Long allowedId) {
		perks.findByNameIgnoreCase(name)
			.filter(existing -> !existing.getId().equals(allowedId))
			.ifPresent(existing -> {
				throw ApiException.conflict("A perk called " + existing.getName() + " already exists.");
			});
	}

	private Perk save(Perk perk) {
		try {
			return perks.saveAndFlush(perk);
		} catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A perk called " + perk.getName() + " already exists.");
		}
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}

	private static String dollars(BigDecimal amount) {
		return "$" + amount.setScale(2).toPlainString();
	}
}
