package com.onebase.credit;

/**
 * A payment took the panel credit down to the low mark (2026-10-28): published by
 * PaymentService, emailed by {@link LowCreditAlerts} once the payment is saved.
 *
 * @param paymentId the payment that crossed the mark
 * @param left credits left after it (may be below zero: the payment is still saved)
 * @param threshold the low mark ({@code onebase.credit.low-threshold}, 50)
 */
public record LowCredit(long paymentId, long left, int threshold) {

	/**
	 * Once per crossing: above the mark before this payment, at or below it after.
	 * Payments made while already low say nothing more; a top-up that brings the credit
	 * back above the mark arms it again.
	 */
	public static boolean crossed(long before, long after, int threshold) {
		return before > threshold && after <= threshold;
	}
}
