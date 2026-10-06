package com.onebase.paymentmethod;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.common.ApiException;
import com.onebase.payment.PaymentRepository;
import com.onebase.paymentmethod.PaymentMethodDtos.PaymentMethodResponse;
import com.onebase.paymentmethod.PaymentMethodDtos.SavePaymentMethodRequest;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Payment methods tab: add, edit, switch on and off. Admins only.
 *
 * <p>Decided 2026-10-04: <b>no delete</b> — a method that is switched off can't
 * be chosen for new payments, and old payments keep it. No balance is typed: a
 * method's balance is the total of the payments recorded on it. Everything is USD.
 *
 * <p>Names are unique without regard to capitals or extra spaces. Every change
 * is written to the Action log, saying what changed.
 */
@Service
public class PaymentMethodService {

	private static final Logger log = LoggerFactory.getLogger(PaymentMethodService.class);

	private final PaymentMethodRepository methods;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final PaymentRepository payments;

	public PaymentMethodService(PaymentMethodRepository methods, UserRepository users, ActionLogService actionLog,
			PaymentRepository payments) {
		this.methods = methods;
		this.users = users;
		this.actionLog = actionLog;
		this.payments = payments;
	}

	@Transactional(readOnly = true)
	public List<PaymentMethodResponse> list() {
		Map<Long, BigDecimal> balances = balances();
		return methods.findAllByOrderByCreatedAtAscIdAsc().stream()
			.map(method -> PaymentMethodResponse.from(method, balances.getOrDefault(method.getId(), BigDecimal.ZERO)))
			.toList();
	}

	@Transactional(readOnly = true)
	public PaymentMethodResponse get(long id) {
		PaymentMethod method = find(id);
		return PaymentMethodResponse.from(method, balances().getOrDefault(method.getId(), BigDecimal.ZERO));
	}

	@Transactional
	public PaymentMethodResponse create(AuthPrincipal admin, SavePaymentMethodRequest request) {
		refuseTaken(request.name(), null);
		PaymentMethod method = saveRefusingDuplicates(new PaymentMethod(request.provider(), request.name(), request.holder(),
			request.cardNetwork(), request.instructions(), request.active()));
		actionLog.record(actor(admin), Action.CREATED, TargetType.PAYMENT_METHOD, method.getId(), method.getName(),
			"Added a " + label(method.getProvider()) + " method" + (method.isActive() ? "" : ", switched off"));
		log.info("User id={} added payment method id={}", admin.userId(), method.getId());
		return PaymentMethodResponse.from(method);
	}

	@Transactional
	public PaymentMethodResponse update(AuthPrincipal admin, long id, SavePaymentMethodRequest request) {
		PaymentMethod method = find(id);
		refuseTaken(request.name(), method.getId());

		List<String> changed = new ArrayList<>();
		if (!method.getName().equals(request.name())) changed.add("name from " + method.getName() + " to " + request.name());
		if (method.getProvider() != request.provider()) {
			changed.add("type from " + label(method.getProvider()) + " to " + label(request.provider()));
		}
		if (!method.getHolder().equals(request.holder())) changed.add("holder to " + request.holder());
		if (method.getCardNetwork() != request.cardNetwork()) changed.add("card network");
		if (!Objects.equals(method.getInstructions(), request.instructions())) changed.add("instructions");

		User actor = actor(admin);
		method.update(request.provider(), request.name(), request.holder(), request.cardNetwork(), request.instructions());
		saveRefusingDuplicates(method);
		if (!changed.isEmpty()) {
			actionLog.record(actor, Action.UPDATED, TargetType.PAYMENT_METHOD, method.getId(), method.getName(),
				"Changed " + String.join(", ", changed));
		}
		// The form has the status too: a change there is logged like the switch.
		switchTo(actor, method, request.active());
		return PaymentMethodResponse.from(method);
	}

	@Transactional
	public PaymentMethodResponse setActive(AuthPrincipal admin, long id, boolean active) {
		PaymentMethod method = find(id);
		switchTo(actor(admin), method, active);
		return PaymentMethodResponse.from(method);
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private void switchTo(User actor, PaymentMethod method, boolean active) {
		if (method.isActive() == active) return;
		method.setActive(active);
		actionLog.record(actor, active ? Action.ACTIVATED : Action.DEACTIVATED, TargetType.PAYMENT_METHOD,
			method.getId(), method.getName(),
			active ? "Method switched on: it can be chosen for new payments"
				: "Method switched off: it can't be chosen for new payments; old payments keep it");
	}

	/** What each method has received from payments. */
	private Map<Long, BigDecimal> balances() {
		Map<Long, BigDecimal> totals = new HashMap<>();
		for (Object[] row : payments.totalsByMethod()) totals.put((Long) row[0], (BigDecimal) row[1]);
		return totals;
	}

	private PaymentMethod find(long id) {
		return methods.findById(id).orElseThrow(() -> ApiException.notFound("This payment method does not exist."));
	}

	private void refuseTaken(String name, Long allowedId) {
		methods.findByNameIgnoreCase(name)
			.filter(existing -> !existing.getId().equals(allowedId))
			.ifPresent(existing -> {
				throw ApiException.conflict("A method called " + existing.getName() + " already exists.");
			});
	}

	/** Two Admins using the same name at the same moment: the database has the last word. */
	private PaymentMethod saveRefusingDuplicates(PaymentMethod method) {
		try {
			return methods.saveAndFlush(method);
		} catch (DataIntegrityViolationException e) {
			throw ApiException.conflict("A method called " + method.getName() + " already exists.");
		}
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}

	private static String label(PaymentMethod.Provider provider) {
		return switch (provider) {
			case PAYPAL -> "PayPal";
			case BINANCE -> "Binance";
			case INTERAC -> "Interac";
			case DEBIT_CARD -> "Debit card";
		};
	}
}
