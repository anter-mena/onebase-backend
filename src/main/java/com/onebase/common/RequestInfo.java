package com.onebase.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is calling, as far as the request says: their address and their browser.
 *
 * <p>Requests arrive through the Next.js server (and later Caddy), which passes
 * the person's own address in {@code X-Forwarded-For} and their browser in
 * {@code User-Agent}. Stored for the sessions list and the Action log only —
 * never used to decide access, since anyone can write these headers.
 */
public final class RequestInfo {

	private RequestInfo() {
	}

	/** The caller's address, or null outside a web request (a startup job, a test helper). */
	public static String clientIp() {
		HttpServletRequest http = current();
		if (http == null) return null;
		String forwarded = http.getHeader("X-Forwarded-For");
		String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
		return ip == null || ip.length() <= 45 ? ip : ip.substring(0, 45);
	}

	public static String userAgent() {
		HttpServletRequest http = current();
		return http == null ? null : http.getHeader(HttpHeaders.USER_AGENT);
	}

	private static HttpServletRequest current() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
			? attributes.getRequest()
			: null;
	}
}
