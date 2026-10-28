package com.onebase.report;

import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.client.Client;
import com.onebase.client.ClientDtos.RenewalRow;
import com.onebase.client.ClientRepository;
import com.onebase.client.ClientService;
import com.onebase.credit.CreditTopup;
import com.onebase.credit.CreditTopupRepository;
import com.onebase.inbox.InboxService;
import com.onebase.payment.Payment;
import com.onebase.payment.PaymentRepository;
import com.onebase.paymentmethod.PaymentMethod;
import com.onebase.paymentmethod.PaymentMethodRepository;
import com.onebase.report.WeeklyReport.Activity;
import com.onebase.report.WeeklyReport.Attention;
import com.onebase.report.WeeklyReport.Clients;
import com.onebase.report.WeeklyReport.Credit;
import com.onebase.report.WeeklyReport.Line;
import com.onebase.report.WeeklyReport.Money;
import com.onebase.report.WeeklyReport.Person;
import com.onebase.report.WeeklyReport.Site;
import com.onebase.seo.SeoService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.whatsapp.WhatsAppMessageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly report's numbers (2026-10-28): a week runs Sunday to Saturday, and the
 * report sent at Sunday 00:00 covers the one that just ended. Money is counted as the
 * Ledger counts it (cash: money in, minus top-ups and perks paid).
 *
 * <p>Gmail and Google Analytics are asked too; either one failing leaves its part
 * "not available" rather than stopping the report.
 */
@Service
public class WeeklyReportService {

	private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);
	/** Names listed under "Needs attention"; the rest are counted. */
	static final int MAX_PEOPLE = 8;

	private final PaymentRepository payments;
	private final CreditTopupRepository topups;
	private final ClientRepository clients;
	private final ClientService clientService;
	private final BrandRepository brands;
	private final PaymentMethodRepository methods;
	private final UserRepository users;
	private final WhatsAppMessageRepository whatsapp;
	private final InboxService inbox;
	private final SeoService seo;
	private final ZoneId zone;

	public WeeklyReportService(PaymentRepository payments, CreditTopupRepository topups, ClientRepository clients,
			ClientService clientService, BrandRepository brands, PaymentMethodRepository methods, UserRepository users,
			WhatsAppMessageRepository whatsapp, InboxService inbox, SeoService seo,
			@Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.topups = topups;
		this.clients = clients;
		this.clientService = clientService;
		this.brands = brands;
		this.methods = methods;
		this.users = users;
		this.whatsapp = whatsapp;
		this.inbox = inbox;
		this.seo = seo;
		this.zone = ZoneId.of(zone);
	}

	/** The Saturday ending the week to report: the week holding {@code day}, else the last one finished. */
	static LocalDate weekEnd(LocalDate day, LocalDate today) {
		return day != null ? day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
			: today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));
	}

	@Transactional(readOnly = true)
	public WeeklyReport report(LocalDate day) {
		LocalDate end = weekEnd(day, LocalDate.now(zone));
		LocalDate start = end.minusDays(6);
		Instant from = start.atStartOfDay(zone).toInstant();
		Instant to = end.plusDays(1).atStartOfDay(zone).toInstant();
		Instant previousFrom = from.minus(java.time.Duration.ofDays(7));

		List<Payment> week = payments.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(from, to);
		List<CreditTopup> weekTopups = topups.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(from, to);
		Money money = money(week, weekTopups);
		Money previousMoney = money(
			payments.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(previousFrom, from),
			topups.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(previousFrom, from));

		Map<Long, String> brandNames = brands.findAll().stream().collect(Collectors.toMap(Brand::getId, Brand::getName));
		Map<Long, PaymentMethod> methodsById = methods.findAll().stream().collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));
		Map<Long, String> userNames = users.findAllById(week.stream().map(Payment::getCreatedBy).filter(Objects::nonNull).distinct().toList())
			.stream().collect(Collectors.toMap(User::getId, User::getFullName));

		List<Line> byBrand = lines(week, p -> brandNames.getOrDefault(p.getBrandId(), "Brand #" + p.getBrandId()));
		List<Line> byAccount = lines(week, p -> account(methodsById.get(p.getPaymentMethodId())));
		List<Line> byPerson = lines(week, p -> userNames.getOrDefault(p.getCreatedBy(), "Someone"));

		List<Client> added = clients.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
		int fromWhatsApp = (int) added.stream().filter(c -> c.getSource() == Client.Source.WHATSAPP).count();
		int becameActive = (int) week.stream().filter(p -> p.getKind() == Payment.Kind.NEW_PLAN).map(Payment::getClientId).distinct().count();
		Clients clientsPart = new Clients(added.size(), fromWhatsApp, added.size() - fromWhatsApp, becameActive,
			payments.clientsCoveredOn(end), payments.clientsCoveredOn(end.minusDays(7)), payments.clientsEndedBetween(start, end).size());

		long toppedUpCredits = weekTopups.stream().mapToLong(CreditTopup::getCredits).sum();
		Credit credit = new Credit(topups.totalCredits() - payments.totalCreditsUsed(), week.stream().mapToLong(Payment::getCreditsUsed).sum(),
			toppedUpCredits, money.topups());

		Object[] received = whatsapp.receivedBetween(from, to).stream().findFirst().orElse(new Object[] {0L, 0L});
		Activity activity = new Activity(((Number) received[0]).longValue(), ((Number) received[1]).longValue(), brandEmails(from, to), byPerson);

		return new WeeklyReport(start, end, start.minusDays(7), end.minusDays(7), end.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
			money, previousMoney, byBrand, byAccount, clientsPart, attention(), credit, activity, sites(start, end), Instant.now());
	}

	private static Money money(List<Payment> week, List<CreditTopup> weekTopups) {
		BigDecimal in = sum(week, Payment::getAmount);
		List<Payment> newPlans = week.stream().filter(p -> p.getKind() == Payment.Kind.NEW_PLAN).toList();
		List<Payment> renewals = week.stream().filter(p -> p.getKind() == Payment.Kind.RENEWAL).toList();
		BigDecimal topupsOut = weekTopups.stream().map(CreditTopup::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal perks = sum(week, Payment::getPerksCost);
		BigDecimal average = week.isEmpty() ? BigDecimal.ZERO : in.divide(BigDecimal.valueOf(week.size()), 2, RoundingMode.HALF_UP);
		return new Money(week.size(), newPlans.size(), sum(newPlans, Payment::getAmount), renewals.size(), sum(renewals, Payment::getAmount),
			in, topupsOut, perks, in.subtract(topupsOut).subtract(perks), average);
	}

	private static BigDecimal sum(List<Payment> list, Function<Payment, BigDecimal> value) {
		return list.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** Totals by name, largest first. */
	private static List<Line> lines(List<Payment> week, Function<Payment, String> name) {
		Map<String, List<Payment>> groups = new HashMap<>();
		week.forEach(p -> groups.computeIfAbsent(name.apply(p), k -> new ArrayList<>()).add(p));
		return groups.entrySet().stream()
			.map(e -> new Line(e.getKey(), e.getValue().size(), sum(e.getValue(), Payment::getAmount)))
			.sorted(Comparator.comparing(Line::amount).reversed().thenComparing(Line::name))
			.toList();
	}

	private static String account(PaymentMethod method) {
		if (method == null) return "Unknown account";
		String provider = switch (method.getProvider()) {
			case PAYPAL -> "PayPal";
			case BINANCE -> "Binance";
			case INTERAC -> "Interac";
			case DEBIT_CARD -> "Debit card";
		};
		return provider + " · " + method.getName();
	}

	private Attention attention() {
		List<RenewalRow> rows = clientService.renewals().stream()
			.filter(row -> Set.of("ENDING_SOON", "CALLBACK").contains(row.group()))
			.toList();
		int endingSoon = (int) rows.stream().filter(row -> row.group().equals("ENDING_SOON")).count();
		List<Person> people = rows.stream().limit(MAX_PEOPLE)
			.map(row -> new Person(row.client().name(), row.client().brandName(),
				row.group().equals("CALLBACK") ? null : row.client().subscriptionEnd(), row.group().equals("CALLBACK")))
			.toList();
		return new Attention(endingSoon, rows.size() - endingSoon, people, Math.max(0, rows.size() - MAX_PEOPLE));
	}

	private Integer brandEmails(Instant from, Instant to) {
		try {
			return inbox.receivedBetween(from, to);
		} catch (RuntimeException e) {
			log.warn("Weekly report: Gmail did not answer ({})", e.getMessage());
			return null;
		}
	}

	/** Sessions per brand with a GA4 property, against the week before. Null if Google did not answer. */
	private List<Site> sites(LocalDate start, LocalDate end) {
		List<Brand> withGa4 = brands.findAll().stream().filter(Brand::isActive).filter(b -> b.getGa4PropertyId() != null).toList();
		// A week still under way (a preview) stops at today: GA4 has no future to read.
		LocalDate today = LocalDate.now(zone);
		String last = (end.isAfter(today) ? today : end).toString();
		try {
			List<Site> sites = new ArrayList<>();
			for (Brand brand : withGa4) {
				SeoService.Totals totals = seo.overview(brand.getId(), "custom", start.toString(), last).totals();
				sites.add(new Site(brand.getName(), totals.sessions(), totals.previousSessions()));
			}
			sites.sort(Comparator.comparingLong(Site::sessions).reversed());
			return sites;
		} catch (RuntimeException e) {
			log.warn("Weekly report: Google Analytics did not answer ({})", e.getMessage());
			return null;
		}
	}
}
