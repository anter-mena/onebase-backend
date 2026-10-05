package com.onebase.client;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.onebase.common.ApiException;

/**
 * Phone numbers in one shape, so a client is found whatever way the number was typed.
 *
 * <p>Every number is stored as E.164 ({@code +212612345678}): "+212 6 12-34 56 78",
 * "00212612345678" and WhatsApp's "212612345678" are the same client. The country is
 * read from the number with Google's libphonenumber — its numbering-plan data ships
 * with the library, so no client's number is sent to an outside service.
 */
final class PhoneNumbers {

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
	static final String NEEDS_COUNTRY_CODE = "Write the number with its country code, like +212 6 12 34 56 78.";
	static final String NOT_A_NUMBER = "This is not a phone number we can use.";

	private PhoneNumbers() {
	}

	/** A number typed by the team, as E.164; null when blank. Refuses a number without its country code. */
	static String normalize(String raw) {
		if (raw == null || raw.isBlank()) return null;
		String cleaned = raw.trim();
		if (cleaned.startsWith("00")) cleaned = "+" + cleaned.substring(2);
		// "06…" is a local number: which country it belongs to can't be told.
		if (!cleaned.startsWith("+")) {
			if (cleaned.replaceAll("[^0-9]", "").startsWith("0")) throw ApiException.badRequest(NEEDS_COUNTRY_CODE);
			cleaned = "+" + cleaned;
		}
		String e164 = parse(cleaned);
		if (e164 == null) throw ApiException.badRequest(NOT_A_NUMBER);
		return e164;
	}

	/** WhatsApp's number (digits with the country code, no "+"), as E.164; null if it can't be read. */
	static String fromWhatsApp(String waId) {
		if (waId == null || !waId.matches("[1-9][0-9]{6,14}")) return null;
		String e164 = parse("+" + waId);
		// Meta's test numbers can be outside the numbering plan: the digits are still the number.
		return e164 != null ? e164 : "+" + waId;
	}

	/** "MA" for a Moroccan number; null when the number doesn't say (a code shared or not tied to a country). */
	static String country(String e164) {
		if (e164 == null) return null;
		try {
			String region = UTIL.getRegionCodeForNumber(UTIL.parse(e164, "ZZ"));
			return region == null || region.length() != 2 || "ZZ".equals(region) ? null : region;
		} catch (NumberParseException e) {
			return null;
		}
	}

	/** Lenient on purpose ("possible", not "valid"): new number ranges reach clients before the library knows them. */
	private static String parse(String withPlus) {
		try {
			PhoneNumber number = UTIL.parse(withPlus, "ZZ");
			if (!UTIL.isPossibleNumber(number)) return null;
			String e164 = UTIL.format(number, PhoneNumberFormat.E164);
			return e164.matches("\\+[1-9][0-9]{6,14}") ? e164 : null;
		} catch (NumberParseException e) {
			return null;
		}
	}
}
