package com.onebase.credit;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.common.ApiException;
import com.onebase.payment.PaymentRepository;
import com.onebase.security.AuthPrincipal;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expenses → Panel credit: top-ups, and what is left.
 *
 * <p>Credit left = all top-ups − credits used by payments (deleted payments
 * give theirs back). It can go below zero: a sale is saved even then. A
 * top-up is never edited or deleted (money history); a mistake is corrected
 * later with a correction line.
 */
@Service
public class CreditService {

	static final BigDecimal MAX_AMOUNT = new BigDecimal("999999.99");

	/** The Panel credit card. Amounts in USD; averageCostPerCredit is null before the first top-up. */
	public record CreditSummary(long totalCredits, long usedCredits, long remainingCredits, BigDecimal totalPaid,
			BigDecimal averageCostPerCredit, LastTopup lastTopup) {
	}

	public record LastTopup(int credits, BigDecimal amount, String note, Instant at) {
	}

	public record TopupRequest(
			@NotNull(message = "Enter how many credits.") Integer credits,
			@NotNull(message = "Enter how much you paid.") BigDecimal amount,
			@Size(max = 255, message = "Keep the note under 255 characters.") String note) {

		public TopupRequest {
			note = note == null || note.isBlank() ? null : note.trim();
		}
	}

	private final CreditTopupRepository topups;
	private final PaymentRepository payments;
	private final UserRepository users;
	private final ActionLogService actionLog;

	public CreditService(CreditTopupRepository topups, PaymentRepository payments, UserRepository users,
			ActionLogService actionLog) {
		this.topups = topups;
		this.payments = payments;
		this.users = users;
		this.actionLog = actionLog;
	}

	@Transactional(readOnly = true)
	public CreditSummary summary() {
		long total = topups.totalCredits();
		long used = payments.totalCreditsUsed();
		BigDecimal paid = topups.totalAmount().setScale(2, RoundingMode.HALF_UP);
		BigDecimal average = total == 0 ? null : paid.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
		LastTopup last = topups.findFirstByOrderByCreatedAtDescIdDesc()
			.map(t -> new LastTopup(t.getCredits(), t.getAmount(), t.getNote(), t.getCreatedAt()))
			.orElse(null);
		return new CreditSummary(total, used, total - used, paid, average, last);
	}

	@Transactional
	public CreditSummary topUp(AuthPrincipal admin, TopupRequest request) {
		int credits = request.credits();
		if (credits <= 0 || credits > 1_000_000) throw ApiException.badRequest("Enter between 1 and 1,000,000 credits.");
		BigDecimal amount = request.amount();
		if (amount.signum() < 0) throw ApiException.badRequest("The amount can't be below $0.");
		if (amount.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("The amount can have at most 2 decimals.");
		if (amount.compareTo(MAX_AMOUNT) > 0) throw ApiException.badRequest("The amount is too high.");
		amount = amount.setScale(2);

		User actor = users.findById(admin.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
		CreditTopup topup = topups.save(new CreditTopup(credits, amount, request.note(), actor.getId()));
		actionLog.record(actor, Action.CREATED, TargetType.PANEL_CREDIT, topup.getId(), "Panel credit",
			"Added " + credits + " credits for $" + amount.toPlainString() + (request.note() == null ? "" : " (" + request.note() + ")"));
		return summary();
	}
}
