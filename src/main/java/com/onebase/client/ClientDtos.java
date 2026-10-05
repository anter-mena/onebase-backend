package com.onebase.client;

import com.onebase.client.Client.Source;
import com.onebase.client.Client.Status;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

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
			Instant updatedAt) {
	}

	/**
	 * Add and Edit send the whole left card. The WhatsApp name is not here: it
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
