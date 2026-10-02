package com.onebase.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The secret part of a one-time link — a password reset or an invitation.
 *
 * <p>{@link #generate()} makes the value that goes into the email; only its
 * {@link #hash} is stored. Whoever reads the database (or a backup of it) still
 * cannot use a link: they would need the value, and only the email has it.
 */
public final class OneTimeTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private OneTimeTokens() {
	}

	/** 32 random bytes, URL-safe — about 43 characters, fine in a link. */
	public static String generate() {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
	}

	public static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
