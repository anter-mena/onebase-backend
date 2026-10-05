package com.onebase.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The WhatsApp number One Base speaks for (Meta Cloud API). Today Meta's free
 * test number; switching to the real number later means changing the phone
 * number ID here, nothing else.
 *
 * <p>All in {@code backend.env} on the server: the system user token, the phone
 * number ID, the app secret (to check Meta's signature on every webhook call) and
 * the verify token (a random word typed once in the Meta dashboard). Without the
 * token or the phone number ID the Inbox answers 503 "not connected".
 */
@Component
public class WhatsAppSettings {

	private final String token;
	private final String phoneNumberId;
	private final String appSecret;
	private final String verifyToken;
	private final String apiVersion;
	private final String template;

	public WhatsAppSettings(
			@Value("${onebase.whatsapp.token:}") String token,
			@Value("${onebase.whatsapp.phone-number-id:}") String phoneNumberId,
			@Value("${onebase.whatsapp.app-secret:}") String appSecret,
			@Value("${onebase.whatsapp.verify-token:}") String verifyToken,
			@Value("${onebase.whatsapp.api-version:v23.0}") String apiVersion,
			@Value("${onebase.whatsapp.template:hello_world}") String template) {
		this.token = token.trim();
		this.phoneNumberId = phoneNumberId.trim();
		this.appSecret = appSecret.trim();
		this.verifyToken = verifyToken.trim();
		this.apiVersion = apiVersion.trim();
		this.template = template.trim();
	}

	public boolean connected() {
		return !token.isEmpty() && !phoneNumberId.isEmpty();
	}

	public String token() { return token; }
	public String phoneNumberId() { return phoneNumberId; }
	public String appSecret() { return appSecret; }
	public String verifyToken() { return verifyToken; }
	public String apiVersion() { return apiVersion; }
	/** The approved template sent when the 24-hour window is closed. Meta's test number has "hello_world". */
	public String template() { return template; }
}
