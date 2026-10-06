package com.onebase.payment;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.client.Client;
import com.onebase.client.ClientRepository;
import com.onebase.common.ApiException;
import com.onebase.credit.CreditTopupRepository;
import com.onebase.payment.Payment.Kind;
import com.onebase.payment.Payment.PerkLine;
import com.onebase.payment.PaymentDtos.CreatePaymentRequest;
import com.onebase.payment.PaymentDtos.CreatedPayment;
import com.onebase.payment.PaymentDtos.PaymentResponse;
import com.onebase.payment.PaymentDtos.PerkChoice;
import com.onebase.payment.PaymentDtos.PerkLineResponse;
import com.onebase.paymentmethod.PaymentMethod;
import com.onebase.paymentmethod.PaymentMethodRepository;
import com.onebase.perk.Perk;
import com.onebase.perk.PerkRepository;
import com.onebase.plan.Plan;
import com.onebase.plan.PlanRepository;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A client's payments: the list, add, and delete (Admins).
 *
 * <p><b>Adding</b> (decided 2026-10-06): the plan, its price, cost and panel
 * credits, and each perk's cost are read from Configuration here — the window
 * only says which ones — and copied into the payment. The amount is what the
 * client paid: the plan's price, or a private price. The payment then puts the
 * client on its brand and makes them Active.
 *
 * <p><b>Dates.</b> A new plan starts today. A renewal starts where the running
 * term ends — or today, when it has already ended — so renewing early never
 * loses days. "Today" is the business day ({@code onebase.timezone}).
 *
 * <p><b>Panel credit.</b> A sale spends the plan's credits. When that takes the
 * balance below zero the payment is still saved — the client has paid — and the
 * answer carries a warning.
 *
 * <p><b>Delete</b> is soft, logged, and Admin-only (SecurityConfig): the payment
 * leaves every total, its credits come back, and the client's dates are worked
 * out again from what remains.
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
	static final BigDecimal MAX_AMOUNT = new BigDecimal("100000");

	private final PaymentRepository payments;
	private final ClientRepository clients;
	private final PlanRepository plans;
	private final PerkRepository perks;
	private final BrandRepository brands;
	private final PaymentMethodRepository methods;
	private final CreditTopupRepository topups;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final ZoneId zone;

	public PaymentService(PaymentRepository payments, ClientRepository clients, PlanRepository plans, PerkRepository perks,
			BrandRepository brands, PaymentMethodRepository methods, CreditTopupRepository topups, UserRepository users,
			ActionLogService actionLog, @Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.clients = clients;
		this.plans = plans;
		this.perks = perks;
		this.brands = brands;
		this.methods = methods;
		this.topups = topups;
		this.users = users;
		this.actionLog = actionLog;
		this.zone = ZoneId.of(zone);
	}

	@Transactional(readOnly = true)
	public List<PaymentResponse> list(long clientId) {
		findClient(clientId);
		return views(payments.findByClientIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(clientId));
	}

	@Transactional
	public CreatedPayment create(AuthPrincipal principal, long clientId, CreatePaymentRequest request) {
		Client client = findClient(clientId);
		Plan plan = plans.findByDevicesAndMonths(request.devices().shortValue(), request.months().shortValue())
			.orElseThrow(() -> ApiException.badRequest("This plan doesn't exist: choose one from the list."));
		Brand brand = brands.findById(request.brandId()).filter(Brand::isActive)
			.orElseThrow(() -> ApiException.badRequest("Choose a brand that is switched on."));
		PaymentMethod method = methods.findById(request.paymentMethodId()).filter(PaymentMethod::isActive)
			.orElseThrow(() -> ApiException.badRequest("Choose a payment method that is switched on."));
		BigDecimal amount = checkedAmount(request.amount());
		List<PerkLine> perkLines = perkLines(request.perks());

		LocalDate today = LocalDate.now(zone);
		LocalDate runningEnd = payments.latestEnd(clientId);
		LocalDate startsOn = request.kind() == Kind.RENEWAL && runningEnd != null && runningEnd.isAfter(today) ? runningEnd : today;
		LocalDate endsOn = startsOn.plusMonths(plan.getMonths());
		int credits = plan.getCredits() == null ? 0 : plan.getCredits();

		User actor = actor(principal);
		Payment payment = payments.saveAndFlush(new Payment(client.getId(), brand.getId(), method.getId(), request.kind(),
			plan.getDevices(), plan.getMonths(), plan.getPrice(), amount, plan.getCost(), perkLines, credits, startsOn,
			endsOn, actor.getId()));
		client.paid(brand.getId());
		clients.save(client);

		String planText = plan.getMonths() + (plan.getMonths() == 1 ? " month" : " months") + " · " + plan.getDevices()
			+ (plan.getDevices() == 1 ? " device" : " devices");
		actionLog.record(actor, Action.CREATED, TargetType.PAYMENT, payment.getId(), client.displayName(),
			"Added a payment of $" + amount.toPlainString() + " (" + planText + ", " + method.getName() + ")"
				+ (amount.compareTo(plan.getPrice()) == 0 ? "" : " — private price, list price $" + plan.getPrice().toPlainString()));
		log.info("User id={} added payment id={} for client id={}", principal.userId(), payment.getId(), client.getId());

		long remaining = topups.totalCredits() - payments.totalCreditsUsed();
		String warning = credits > 0 && remaining < 0
			? "Saved — but the panel credit is now at " + remaining + ". Top it up in Configuration → Expenses."
			: null;
		return new CreatedPayment(views(List.of(payment)).getFirst(), warning);
	}

	@Transactional
	public void delete(AuthPrincipal principal, long paymentId) {
		Payment payment = payments.findById(paymentId).filter(p -> !p.isDeleted())
			.orElseThrow(() -> ApiException.notFound("This payment does not exist."));
		payment.delete();
		payments.save(payment);
		Client client = clients.findById(payment.getClientId()).orElseThrow();
		// Its time goes with it: if nothing paid runs past today any more, they are Inactive.
		LocalDate runningEnd = payments.latestEnd(client.getId());
		if (client.getStatus() == Client.Status.ACTIVE && (runningEnd == null || runningEnd.isBefore(LocalDate.now(zone)))) {
			client.lapsed();
			clients.save(client);
		}
		actionLog.record(actor(principal), Action.DELETED, TargetType.PAYMENT, payment.getId(), client.displayName(),
			"Deleted a payment of $" + payment.getAmount().toPlainString() + " from " + payment.getCreatedAt().atZone(zone).toLocalDate()
				+ "; its credits came back");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private List<PaymentResponse> views(List<Payment> rows) {
		Map<Long, Brand> brandsById = brands.findAllById(rows.stream().map(Payment::getBrandId).distinct().toList())
			.stream().collect(Collectors.toMap(Brand::getId, Function.identity()));
		Map<Long, PaymentMethod> methodsById = methods.findAllById(rows.stream().map(Payment::getPaymentMethodId).distinct().toList())
			.stream().collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));
		return rows.stream().map(p -> {
			Brand brand = brandsById.get(p.getBrandId());
			PaymentMethod method = methodsById.get(p.getPaymentMethodId());
			return new PaymentResponse(p.getId(), p.getKind(), p.getDevices(), p.getMonths(), p.getPlanPrice(), p.getAmount(),
				p.getPlanCost(), p.getPerksCost(), p.expense(), p.getCreditsUsed(), p.getCreatedAt().atZone(zone).toLocalDate(),
				p.getStartsOn(), p.getEndsOn(), p.getBrandId(), brand == null ? null : brand.getName(),
				brand == null ? null : brand.logoUrl(), p.getPaymentMethodId(),
				method == null ? null : method.getProvider().name(), method == null ? null : method.getName(),
				p.getPerks().stream().map(l -> new PerkLineResponse(l.perkId(), l.name(), l.quantity(), l.unitCost())).toList(),
				p.getCreatedAt());
		}).toList();
	}

	private List<PerkLine> perkLines(List<PerkChoice> choices) {
		List<PerkLine> lines = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (PerkChoice choice : choices) {
			if (choice.quantity() == 0) continue;
			if (choice.quantity() < 0 || choice.quantity() > 20) throw ApiException.badRequest("A perk count goes from 1 to 20.");
			if (!seen.add(choice.perkId())) throw ApiException.badRequest("Each perk can be chosen once.");
			Perk perk = perks.findById(choice.perkId()).filter(Perk::isActive)
				.orElseThrow(() -> ApiException.badRequest("Choose perks that are switched on."));
			lines.add(new PerkLine(perk.getId(), perk.getName(), choice.quantity(), perk.getCost()));
		}
		return lines;
	}

	private static BigDecimal checkedAmount(BigDecimal amount) {
		if (amount.signum() <= 0) throw ApiException.badRequest("The amount must be more than $0.");
		if (amount.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("The amount can have at most 2 decimals.");
		if (amount.compareTo(MAX_AMOUNT) > 0) throw ApiException.badRequest("The amount is too high.");
		return amount.setScale(2);
	}

	private Client findClient(long id) {
		return clients.findById(id).filter(client -> !client.isDeleted())
			.orElseThrow(() -> ApiException.notFound("This client does not exist."));
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}
}
