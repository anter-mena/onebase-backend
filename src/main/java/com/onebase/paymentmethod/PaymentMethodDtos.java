package com.onebase.paymentmethod;

import com.onebase.paymentmethod.PaymentMethod.CardNetwork;
import com.onebase.paymentmethod.PaymentMethod.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** What crosses the wire for the Payment methods tab. Amounts are USD. */
public final class PaymentMethodDtos {

	private PaymentMethodDtos() {
	}

	/**
	 * One method.
	 *
	 * @param balance the total received on it, in USD (deleted payments left out)
	 */
	public record PaymentMethodResponse(
			long id,
			Provider provider,
			String name,
			String holder,
			CardNetwork cardNetwork,
			String instructions,
			boolean active,
			BigDecimal balance,
			Instant createdAt) {

		static PaymentMethodResponse from(PaymentMethod method) {
			return from(method, BigDecimal.ZERO);
		}

		static PaymentMethodResponse from(PaymentMethod method, BigDecimal balance) {
			return new PaymentMethodResponse(method.getId(), method.getProvider(), method.getName(), method.getHolder(),
				method.getCardNetwork(), method.getInstructions(), method.isActive(), balance.setScale(2),
				method.getCreatedAt());
		}
	}

	/** Add and Edit send the whole method. {@code active} sets the status (the form has it too). */
	public record SavePaymentMethodRequest(
			@NotNull(message = "Choose a type.") Provider provider,
			@NotBlank(message = "Enter the method's name.") @Size(max = 100, message = "Keep the name under 100 characters.") String name,
			@NotBlank(message = "Enter the holder's name.") @Size(max = 120, message = "Keep the holder's name under 120 characters.") String holder,
			@NotNull(message = "Choose the card network.") CardNetwork cardNetwork,
			@Size(max = 1000, message = "Keep the instructions under 1000 characters.") String instructions,
			Boolean active) {

		/** Tidy text: no spaces around, single spaces inside names; empty instructions are none. */
		public SavePaymentMethodRequest {
			name = name == null ? null : name.trim().replaceAll("\\s+", " ");
			holder = holder == null ? null : holder.trim().replaceAll("\\s+", " ");
			instructions = instructions == null || instructions.isBlank() ? null : instructions.trim();
			active = !Boolean.FALSE.equals(active);
		}
	}

	public record StatusRequest(@NotNull(message = "Say whether the method is active.") Boolean active) {
	}
}
