package com.onebase.payment;

import com.onebase.payment.Payment.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What crosses the wire for a client's payments. USD. */
public final class PaymentDtos {

	private PaymentDtos() {
	}

	public record PerkLineResponse(long perkId, String name, int quantity, BigDecimal unitCost) {
	}

	/**
	 * One payment.
	 *
	 * @param expense plan cost + perks; null when the plan had no cost on file
	 * @param paidOn the payment date in the business time zone
	 */
	public record PaymentResponse(
			long id,
			Kind kind,
			int devices,
			int months,
			BigDecimal planPrice,
			BigDecimal amount,
			BigDecimal planCost,
			BigDecimal perksCost,
			BigDecimal expense,
			int creditsUsed,
			LocalDate paidOn,
			LocalDate startsOn,
			LocalDate endsOn,
			long brandId,
			String brandName,
			String brandLogoUrl,
			long paymentMethodId,
			String paymentProvider,
			String paymentMethodName,
			List<PerkLineResponse> perks,
			Instant createdAt) {
	}

	/** One Ledger line: a payment, and whose it is. */
	public record LedgerRow(PaymentResponse payment, long clientId, String clientName) {
	}

	/** A panel-credit top-up: money out on the day it was paid. */
	public record TopupRow(long id, int credits, BigDecimal amount, String note, Instant createdAt, String createdByName) {
	}

	/**
	 * The Ledger for a period, newest first; `methodId` when filtered to one account.
	 * A cash view (decided 2026-10-16).
	 *
	 * @param topups   the period's top-ups, newest first; empty when filtered to an account (a top-up is paid from none)
	 * @param moneyIn  what the rows' payments brought in
	 * @param moneyOut the top-ups listed + the perks cost of the rows' payments
	 * @param net      moneyIn − moneyOut
	 */
	public record Ledger(String range, LocalDate start, LocalDate end, Long methodId, List<LedgerRow> rows,
			List<TopupRow> topups, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal net) {
	}

	/** A payment just added, and a warning when it took the panel credit below zero. */
	public record CreatedPayment(PaymentResponse payment, String warning) {
	}

	public record PerkChoice(@NotNull(message = "Say which perk.") Long perkId, @NotNull(message = "Say how many.") Integer quantity) {
	}

	/** The Add payment window. The prices and costs are read from Configuration here, not sent. */
	public record CreatePaymentRequest(
			@NotNull(message = "Choose New plan or Renewal.") Kind kind,
			@NotNull(message = "Choose the devices.") Integer devices,
			@NotNull(message = "Choose the months.") Integer months,
			@NotNull(message = "Enter the amount paid.") BigDecimal amount,
			@NotNull(message = "Choose a brand.") Long brandId,
			@NotNull(message = "Choose where it was paid to.") Long paymentMethodId,
			@Valid @Size(max = 20, message = "Too many perks.") List<PerkChoice> perks) {

		public CreatePaymentRequest {
			perks = perks == null ? List.of() : perks;
		}
	}
}
