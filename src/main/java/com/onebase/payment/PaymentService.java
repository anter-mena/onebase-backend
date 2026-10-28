package com.onebase.payment;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.client.Client;
import com.onebase.client.ClientRepository;
import com.onebase.common.ApiException;
import com.onebase.credit.CreditTopup;
import com.onebase.credit.CreditTopupRepository;
import com.onebase.payment.Payment.Kind;
import com.onebase.payment.Payment.PerkLine;
import com.onebase.payment.PaymentDtos.CreatePaymentRequest;
import com.onebase.payment.PaymentDtos.CreatedPayment;
import com.onebase.payment.PaymentDtos.PaymentResponse;
import com.onebase.payment.PaymentDtos.PerkChoice;
import com.onebase.payment.PaymentDtos.PerkLineResponse;
import com.onebase.payment.PaymentDtos.ProofFile;
import com.onebase.payment.PaymentDtos.ProofResponse;
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
import org.springframework.web.multipart.MultipartFile;

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
 * <p><b>Proof</b> (2026-10-28, required): a payment is added with its proof files —
 * an invoice, a screenshot, a receipt email ({@link ProofRules}) — checked before
 * anything is saved, and saved with the payment, so neither exists without the other.
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
	private final PaymentProofRepository proofs;
	private final ClientRepository clients;
	private final PlanRepository plans;
	private final PerkRepository perks;
	private final BrandRepository brands;
	private final PaymentMethodRepository methods;
	private final CreditTopupRepository topups;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final ZoneId zone;

	public PaymentService(PaymentRepository payments, PaymentProofRepository proofs, ClientRepository clients, PlanRepository plans, PerkRepository perks,
			BrandRepository brands, PaymentMethodRepository methods, CreditTopupRepository topups, UserRepository users,
			ActionLogService actionLog, @Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.proofs = proofs;
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

	/** Always with its proof: the files are checked first, and saved with the payment or not at all. */
	@Transactional
	public CreatedPayment createWithProof(AuthPrincipal principal, long clientId, CreatePaymentRequest request,
			List<MultipartFile> files) {
		List<ProofRules.Checked> proofFiles = ProofRules.check(files);
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
		proofFiles.forEach(file -> proofs.save(new PaymentProof(payment.getId(), file)));
		client.paid(brand.getId());
		clients.save(client);

		String planText = plan.getMonths() + (plan.getMonths() == 1 ? " month" : " months") + " · " + plan.getDevices()
			+ (plan.getDevices() == 1 ? " device" : " devices");
		actionLog.record(actor, Action.CREATED, TargetType.PAYMENT, payment.getId(), client.displayName(),
			"Added a payment of $" + amount.toPlainString() + " (" + planText + ", " + method.getName() + ")"
				+ (amount.compareTo(plan.getPrice()) == 0 ? "" : " — private price, list price $" + plan.getPrice().toPlainString())
				+ ", with " + proofFiles.size() + (proofFiles.size() == 1 ? " proof file" : " proof files"));
		log.info("User id={} added payment id={} for client id={}", principal.userId(), payment.getId(), client.getId());

		long remaining = topups.totalCredits() - payments.totalCreditsUsed();
		String warning = credits > 0 && remaining < 0
			? "Saved — but the panel credit is now at " + remaining + ". Top it up in Configuration → Expenses."
			: null;
		return new CreatedPayment(views(List.of(payment)).getFirst(), warning);
	}

	/** One proof file, only through its own client and payment (a payment that was deleted has none to show). */
	@Transactional(readOnly = true)
	public ProofFile proof(long clientId, long paymentId, long proofId) {
		Payment payment = payments.findById(paymentId).filter(p -> !p.isDeleted() && p.getClientId() == clientId)
			.orElseThrow(() -> ApiException.notFound("This payment does not exist."));
		PaymentProof proof = proofs.findById(proofId).filter(p -> p.getPaymentId().equals(payment.getId()))
			.orElseThrow(() -> ApiException.notFound("This proof does not exist."));
		return new ProofFile(proof.getData(), proof.getMime(), proof.getFilename());
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

	/**
	 * The Ledger (decided 2026-10-06): every payment received in the period, newest
	 * first, optionally only those paid to one account. Admins only (SecurityConfig).
	 *
	 * <p>A cash view (decided 2026-10-16): the panel-credit top-ups paid in the period
	 * are listed as money out, with the perks of the payments. A top-up is paid from no
	 * account, so a Ledger filtered to one has none.
	 */
	@Transactional(readOnly = true)
	public PaymentDtos.Ledger ledger(Long methodId, String range, String from, String to) {
		com.onebase.seo.SeoPeriod period = com.onebase.seo.SeoPeriod.of(range, from, to, LocalDate.now(zone));
		java.time.Instant start = period.start().atStartOfDay(zone).toInstant();
		java.time.Instant end = period.end().plusDays(1).atStartOfDay(zone).toInstant();
		List<Payment> rows = payments.findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
			start, end);
		if (methodId != null) rows = rows.stream().filter(p -> methodId.equals(p.getPaymentMethodId())).toList();
		Map<Long, Client> owners = clients.findAllById(rows.stream().map(Payment::getClientId).distinct().toList()).stream()
			.collect(Collectors.toMap(Client::getId, Function.identity()));
		List<PaymentResponse> views = views(rows);
		List<PaymentDtos.LedgerRow> lines = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Client owner = owners.get(rows.get(i).getClientId());
			lines.add(new PaymentDtos.LedgerRow(views.get(i), rows.get(i).getClientId(), owner == null ? "—" : owner.displayName()));
		}
		List<CreditTopup> paidOut = methodId != null ? List.of()
			: topups.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(start, end);
		Map<Long, String> buyers = users.findAllById(paidOut.stream().map(CreditTopup::getCreatedBy)
				.filter(java.util.Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(User::getId, User::getFullName));
		List<PaymentDtos.TopupRow> topupRows = paidOut.stream().map(t -> new PaymentDtos.TopupRow(t.getId(), t.getCredits(),
			t.getAmount(), t.getNote(), t.getCreatedAt(), t.getCreatedBy() == null ? null : buyers.get(t.getCreatedBy()))).toList();

		BigDecimal moneyIn = rows.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
		BigDecimal moneyOut = paidOut.stream().map(CreditTopup::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
			.add(rows.stream().map(Payment::getPerksCost).reduce(BigDecimal.ZERO, BigDecimal::add)).setScale(2);
		return new PaymentDtos.Ledger(period.range(), period.start(), period.end(), methodId, lines, topupRows, moneyIn,
			moneyOut, moneyIn.subtract(moneyOut));
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private List<PaymentResponse> views(List<Payment> rows) {
		Map<Long, Brand> brandsById = brands.findAllById(rows.stream().map(Payment::getBrandId).distinct().toList())
			.stream().collect(Collectors.toMap(Brand::getId, Function.identity()));
		Map<Long, PaymentMethod> methodsById = methods.findAllById(rows.stream().map(Payment::getPaymentMethodId).distinct().toList())
			.stream().collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));
		Map<Long, List<ProofResponse>> proofsByPayment = rows.isEmpty() ? Map.of()
			: proofs.findByPaymentIdInOrderByIdAsc(rows.stream().map(Payment::getId).toList()).stream()
				.collect(Collectors.groupingBy(PaymentProofRepository.Info::getPaymentId,
					Collectors.mapping(p -> new ProofResponse(p.getId(), p.getFilename(), p.getMime(), p.getSizeBytes()), Collectors.toList())));
		return rows.stream().map(p -> {
			Brand brand = brandsById.get(p.getBrandId());
			PaymentMethod method = methodsById.get(p.getPaymentMethodId());
			return new PaymentResponse(p.getId(), p.getKind(), p.getDevices(), p.getMonths(), p.getPlanPrice(), p.getAmount(),
				p.getPlanCost(), p.getPerksCost(), p.expense(), p.getCreditsUsed(), p.getCreatedAt().atZone(zone).toLocalDate(),
				p.getStartsOn(), p.getEndsOn(), p.getBrandId(), brand == null ? null : brand.getName(),
				brand == null ? null : brand.logoUrl(), p.getPaymentMethodId(),
				method == null ? null : method.getProvider().name(), method == null ? null : method.getName(),
				p.getPerks().stream().map(l -> new PerkLineResponse(l.perkId(), l.name(), l.quantity(), l.unitCost())).toList(),
				proofsByPayment.getOrDefault(p.getId(), List.of()), p.getCreatedAt());
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
