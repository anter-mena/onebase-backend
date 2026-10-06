package com.onebase.client;

import com.onebase.client.Client.Source;
import com.onebase.client.Client.Status;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What crosses the wire for the Clients screens. */
public final class ClientDtos {

	private ClientDtos() {
	}

	/**
	 * One client.
	 *
	 * @param name what the screens call them: the full name, else the WhatsApp name, else the phone
	 * @param country ISO code read from the phone ("MA"), or null when the number doesn't say
	 * @param brandLogoUrl our own address for the brand's logo, or null
	 * @param conversationId their WhatsApp conversation, or null when they never wrote
	 * @param orders, revenue from their payments (deleted ones left out); revenue in USD
	 * @param devices, months, paymentProvider, paymentMethodName the latest payment's; null before the first
	 * @param subscriptionStart the latest payment's start; null before the first
	 * @param subscriptionEnd when their paid time runs out; null before the first payment
	 * @param orderTrend payments in each of the last four quarters, oldest first
	 * @param statusChangedAt when the status last changed (a trial's start)
	 */
	public record ClientResponse(
			long id,
			String name,
			String fullName,
			String username,
			String email,
			String phone,
			String country,
			Long brandId,
			String brandName,
			String brandLogoUrl,
			Status status,
			Source source,
			String note,
			Long conversationId,
			Instant createdAt,
			Instant updatedAt,
			long orders,
			BigDecimal revenue,
			Integer devices,
			Integer months,
			LocalDate subscriptionStart,
			LocalDate subscriptionEnd,
			String paymentProvider,
			String paymentMethodName,
			List<Integer> orderTrend,
			Instant statusChangedAt) {
	}

	/**
	 * One row of Renewals.
	 *
	 * @param group ENDING_SOON (Active, 10 days or less left), CALLBACK (trial over),
	 *     PENDING (waiting for their payment) or INACTIVE (plan ended)
	 * @param daysLeft days until the paid time ends — negative once it has; null without a plan
	 */
	public record RenewalRow(ClientResponse client, String group, Long daysLeft) {
	}

	/**
	 * Edit sends the whole left card. The WhatsApp name is not here: it
	 * follows the client's profile. The note has its own call.
	 */
	public record SaveClientRequest(
			@Size(max = 120, message = "Keep the full name under 120 characters.") String fullName,
			@Email(message = "This email address doesn't look right.")
			@Size(max = 255, message = "Keep the email under 255 characters.") String email,
			@Size(max = 30, message = "This phone number is too long.") String phone,
			Long brandId,
			@NotNull(message = "Choose a status.") Status status) {

		/** Tidy text: no spaces around, single spaces inside the name; empty is none. */
		public SaveClientRequest {
			fullName = fullName == null || fullName.isBlank() ? null : fullName.trim().replaceAll("\\s+", " ");
			email = email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
			phone = phone == null || phone.isBlank() ? null : phone.trim();
		}
	}

	public record NoteRequest(@Size(max = 5000, message = "Keep the note under 5000 characters.") String note) {

		public NoteRequest {
			note = note == null || note.isBlank() ? null : note.strip();
		}
	}
}
