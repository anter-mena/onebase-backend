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
