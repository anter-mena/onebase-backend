package com.onebase.dashboard;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.client.Client;
import com.onebase.client.ClientDtos.RenewalRow;
import com.onebase.client.ClientRepository;
import com.onebase.client.ClientService;
import com.onebase.common.ApiException;
import com.onebase.credit.CreditService;
import com.onebase.credit.CreditService.CreditSummary;
import com.onebase.payment.Payment;
import com.onebase.payment.PaymentRepository;
import com.onebase.paymentmethod.PaymentMethod;
import com.onebase.paymentmethod.PaymentMethodRepository;
import com.onebase.security.AuthPrincipal;
import com.onebase.seo.SeoPeriod;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Dashboard, worked out from the payments (built 2026-10-06). Admins only.
 *
 * <p><b>One period scopes the page</b> — the SEO page's filter: today, yesterday,
 * 7 days, this month, this year, or custom; the comparison is the same length just
 * before. "Total" and the clients counts are all-time; the monthly target is
 * always the current month. USD, business time zone.
 */
@Service
public class DashboardService {

	/** How many people "Needs chasing" lists. */
	static final int CHASE_ROWS = 6;

	public record Point(String start, BigDecimal newRevenue, BigDecimal renewals, BigDecimal expenses) {
	}

	/**
	 * The period's figures and the previous period's, for the change cues.
	 *
	 * @param uncosted payments whose plan had no cost on file (counted as $0 cost)
	 */
	public record Totals(BigDecimal revenue, BigDecimal newRevenue, BigDecimal renewals, BigDecimal expenses, BigDecimal net,
			long payments, long uncosted, BigDecimal previousRevenue, BigDecimal previousExpenses) {
	}

	public record BrandShare(long id, String name, String logoUrl, BigDecimal revenue, long clients) {
	}

	/** On a paid plan now, everyone on the books, and on a paid plan 30 days ago. */
	public record ActiveClients(long active, long total, long activeMonthAgo) {
	}

	/** This month's target (null until set) and what it has earned so far. */
	public record Target(LocalDate month, BigDecimal target, BigDecimal earned) {
	}

	/** One account in the card carousel: its all-time balance and the period's figures. */
	public record MethodCard(long id, String provider, String name, String holder, String cardNetwork, BigDecimal balance,
			BigDecimal received, long payments, LocalDate lastPaidOn) {
	}

	public record Overview(String range, LocalDate start, LocalDate end, String step, List<Point> points, Totals totals,
			BigDecimal lifetimeRevenue, long clientCount, List<BrandShare> brands, ActiveClients active, Target target,
			CreditSummary credit, List<MethodCard> methods, List<RenewalRow> chase) {
	}

	private final PaymentRepository payments;
	private final ClientRepository clients;
	private final ClientService clientService;
	private final BrandRepository brands;
	private final PaymentMethodRepository methods;
	private final CreditService credit;
	private final MonthlyTargetRepository targets;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final ZoneId zone;

	public DashboardService(PaymentRepository payments, ClientRepository clients, ClientService clientService,
			BrandRepository brands, PaymentMethodRepository methods, CreditService credit, MonthlyTargetRepository targets,
			UserRepository users, ActionLogService actionLog, @Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.clients = clients;
		this.clientService = clientService;
		this.brands = brands;
		this.methods = methods;
		this.credit = credit;
		this.targets = targets;
		this.users = users;
		this.actionLog = actionLog;
		this.zone = ZoneId.of(zone);
	}

	@Transactional(readOnly = true)
	public Overview overview(String range, String from, String to) {
		LocalDate today = LocalDate.now(zone);
		SeoPeriod period = SeoPeriod.of(range, from, to, today);
		List<Payment> all = payments.findByDeletedAtIsNull();
		List<Payment> now = within(all, period.start(), period.end());
		List<Payment> before = within(all, period.previousStart(), period.previousEnd());

		BigDecimal newRevenue = sum(now.stream().filter(p -> p.getKind() == Payment.Kind.NEW_PLAN).map(Payment::getAmount).toList());
		BigDecimal renewals = sum(now.stream().filter(p -> p.getKind() == Payment.Kind.RENEWAL).map(Payment::getAmount).toList());
		BigDecimal expenses = sum(now.stream().map(DashboardService::cost).toList());
		Totals totals = new Totals(newRevenue.add(renewals), newRevenue, renewals, expenses, newRevenue.add(renewals).subtract(expenses),
			now.size(), now.stream().filter(p -> p.expense() == null).count(),
			sum(before.stream().map(Payment::getAmount).toList()), sum(before.stream().map(DashboardService::cost).toList()));

		// Brands: every brand switched on (an empty one is a fact worth seeing), with the period's revenue.
		Map<Long, Long> clientsPerBrand = new HashMap<>();
		for (Object[] row : clients.countByBrand()) clientsPerBrand.put((Long) row[0], (Long) row[1]);
		Map<Long, BigDecimal> revenuePerBrand = new HashMap<>();
		for (Payment p : now) revenuePerBrand.merge(p.getBrandId(), p.getAmount(), BigDecimal::add);
		List<BrandShare> brandShares = brands.findAll().stream()
			.filter(b -> b.isActive() || revenuePerBrand.containsKey(b.getId()))
			.map(b -> new BrandShare(b.getId(), b.getName(), b.logoUrl(), revenuePerBrand.getOrDefault(b.getId(), BigDecimal.ZERO).setScale(2),
				clientsPerBrand.getOrDefault(b.getId(), 0L)))
			.sorted(Comparator.comparing(BrandShare::revenue).reversed())
			.toList();

		List<Client> onBooks = clients.findByDeletedAtIsNullOrderByCreatedAtDescIdDesc();
		ActiveClients active = new ActiveClients(onBooks.stream().filter(c -> c.getStatus() == Client.Status.ACTIVE).count(),
			onBooks.size(), payments.clientsCoveredOn(today.minusDays(30)));

		LocalDate month = today.withDayOfMonth(1);
		Target target = new Target(month, targets.findById(month).map(MonthlyTarget::getTarget).orElse(null),
			sum(within(all, month, today).stream().map(Payment::getAmount).toList()));

		// The carousel: accounts switched on, with what each took in the period.
		Map<Long, BigDecimal> balances = new HashMap<>();
		for (Payment p : all) balances.merge(p.getPaymentMethodId(), p.getAmount(), BigDecimal::add);
		List<MethodCard> cards = methods.findAllByOrderByCreatedAtAscIdAsc().stream().filter(PaymentMethod::isActive).map(m -> {
			List<Payment> mine = now.stream().filter(p -> p.getPaymentMethodId().equals(m.getId())).toList();
			LocalDate last = all.stream().filter(p -> p.getPaymentMethodId().equals(m.getId()))
				.map(p -> p.getCreatedAt().atZone(zone).toLocalDate()).max(Comparator.naturalOrder()).orElse(null);
			return new MethodCard(m.getId(), m.getProvider().name(), m.getName(), m.getHolder(), m.getCardNetwork().name(),
				balances.getOrDefault(m.getId(), BigDecimal.ZERO).setScale(2), sum(mine.stream().map(Payment::getAmount).toList()),
				mine.size(), last);
		}).toList();

		// Needs chasing: Renewals' most urgent, without plans that ended long ago.
		List<RenewalRow> chase = clientService.renewals().stream()
			.filter(row -> !(row.group().equals("INACTIVE") && (row.daysLeft() == null || row.daysLeft() < -30)))
			.limit(CHASE_ROWS)
			.toList();

		return new Overview(period.range(), period.start(), period.end(), period.step().name(), points(now, period), totals,
			sum(all.stream().map(Payment::getAmount).toList()), onBooks.size(), brandShares, active, target, credit.summary(), cards,
			chase);
	}

	/** The pencil on "Monthly target": this month's target. Logged. */
	@Transactional
	public Target setTarget(AuthPrincipal principal, BigDecimal value) {
		if (value == null || value.signum() <= 0) throw ApiException.badRequest("Enter a target above $0.");
		if (value.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("The target can have at most 2 decimals.");
		if (value.compareTo(new BigDecimal("10000000")) > 0) throw ApiException.badRequest("The target is too high.");
		BigDecimal amount = value.setScale(2);
		LocalDate today = LocalDate.now(zone);
		LocalDate month = today.withDayOfMonth(1);
		User actor = users.findById(principal.userId()).orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
		MonthlyTarget row = targets.findById(month).orElse(null);
		if (row == null) targets.save(new MonthlyTarget(month, amount, actor.getId()));
		else {
			row.set(amount, actor.getId());
			targets.save(row);
		}
		actionLog.record(actor, Action.UPDATED, TargetType.WORKSPACE, null, "Monthly target",
			"Set the " + month.format(DateTimeFormatter.ofPattern("MMMM yyyy")) + " target to $" + amount.toPlainString());
		BigDecimal earned = sum(within(payments.findByDeletedAtIsNull(), month, today).stream().map(Payment::getAmount).toList());
		return new Target(month, amount, earned);
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private List<Payment> within(List<Payment> rows, LocalDate start, LocalDate end) {
		Instant from = start.atStartOfDay(zone).toInstant();
		Instant to = end.plusDays(1).atStartOfDay(zone).toInstant();
		return rows.stream().filter(p -> !p.getCreatedAt().isBefore(from) && p.getCreatedAt().isBefore(to)).toList();
	}

	/** The chart's columns, at the period's step; empty ones included so the axis is even. */
	private List<Point> points(List<Payment> rows, SeoPeriod period) {
		Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();
		switch (period.step()) {
			case HOUR -> {
				for (int h = 0; h < 24; h++) buckets.put(period.start() + "T" + String.format("%02d", h) + ":00", zeros());
			}
			case DAY -> {
				for (LocalDate d = period.start(); !d.isAfter(period.end()); d = d.plusDays(1)) buckets.put(d.toString(), zeros());
			}
			case WEEK -> {
				for (LocalDate d = period.start(); !d.isAfter(period.end()); d = d.plusDays(7)) buckets.put(d.toString(), zeros());
			}
			case MONTH -> {
				for (LocalDate m = period.start().withDayOfMonth(1); !m.isAfter(period.end()); m = m.plusMonths(1)) {
					buckets.put((m.isBefore(period.start()) ? period.start() : m).toString(), zeros());
				}
			}
		}
		List<String> keys = new ArrayList<>(buckets.keySet());
		for (Payment p : rows) {
			ZonedDateTime at = p.getCreatedAt().atZone(zone);
			LocalDate day = at.toLocalDate();
			String key = switch (period.step()) {
				case HOUR -> period.start() + "T" + String.format("%02d", at.getHour()) + ":00";
				case DAY -> day.toString();
				case WEEK -> keys.get((int) Math.min(keys.size() - 1, ChronoUnit.DAYS.between(period.start(), day) / 7));
				case MONTH -> {
					LocalDate first = day.withDayOfMonth(1);
					yield (first.isBefore(period.start()) ? period.start() : first).toString();
				}
			};
			BigDecimal[] bucket = buckets.get(key);
			if (bucket == null) continue;
			if (p.getKind() == Payment.Kind.NEW_PLAN) bucket[0] = bucket[0].add(p.getAmount());
			else bucket[1] = bucket[1].add(p.getAmount());
			bucket[2] = bucket[2].add(cost(p));
		}
		return buckets.entrySet().stream()
			.map(e -> new Point(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
			.toList();
	}

	private static BigDecimal[] zeros() {
		return new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) };
	}

	/** A payment's cost; one with no plan cost on file counts as nothing (and is counted in `uncosted`). */
	private static BigDecimal cost(Payment p) {
		return p.expense() == null ? p.getPerksCost() : p.expense();
	}

	private static BigDecimal sum(List<BigDecimal> values) {
		return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
	}
}
