package com.onebase.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Signs and reads access tokens.
 *
 * <p>A token says which session it belongs to ({@code sid}) and nothing the
 * backend relies on without checking: role and status are re-read from the
 * database on every request, so a demoted or deactivated user loses access at
 * once. The {@code role} claim is there only so the frontend's {@code proxy.ts}
 * can pick the right screen without a round trip.
 *
 * <p>⚠️ Unlike the LMS there is no random fallback key: a missing
 * {@code JWT_SECRET} stops the app at startup. A fallback silently logs
 * everyone out on each restart, which is worse than a clear failure.
 */
@Service
public class JwtService {

	public static final String CLAIM_SESSION = "sid";
	public static final String CLAIM_ROLE = "role";

	private final SecretKey key;
	private final Duration ttl;

	public JwtService(@Value("${onebase.jwt.secret}") String secret,
			@Value("${onebase.jwt.ttl:PT12H}") Duration ttl) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 characters (got " + bytes.length + ").");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
		this.ttl = ttl;
	}

	public String issue(long userId, String role, UUID sessionId, Instant expiresAt) {
		return Jwts.builder()
			.subject(Long.toString(userId))
			.claim(CLAIM_SESSION, sessionId.toString())
			.claim(CLAIM_ROLE, role)
			.issuedAt(new Date())
			.expiration(Date.from(expiresAt))
			.signWith(key)
			.compact();
	}

	/** Verifies the signature and expiry; throws {@code JwtException} otherwise. */
	public Claims parse(String token) {
		return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
	}

	public Duration ttl() {
		return ttl;
	}
}
