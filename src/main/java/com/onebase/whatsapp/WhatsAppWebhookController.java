package com.onebase.whatsapp;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Where Meta sends new messages and delivery statuses. Public (Meta has no One
 * Base account), so it trusts nothing it cannot check:
 *
 * <ul>
 *   <li>GET (Meta's one-time check when the address is saved): answers only with
 *       the verify token set in {@code WHATSAPP_VERIFY_TOKEN};</li>
 *   <li>POST: every call carries {@code X-Hub-Signature-256}, an HMAC of the exact
 *       body with the app secret. A call without a valid signature is refused, so
 *       nobody can push fake messages into the Inbox.</li>
 * </ul>
 *
 * <p>Meta needs HTTPS: the address it calls is the frontend's
 * ({@code /webhooks/whatsapp} on Vercel), which passes the body and the signature
 * here unchanged.
 */
@Hidden
@RestController
@RequestMapping("/api/whatsapp/webhook")
public class WhatsAppWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

	private final WhatsAppService service;
	private final WhatsAppSettings settings;
	private final ObjectMapper json;

	public WhatsAppWebhookController(WhatsAppService service, WhatsAppSettings settings, ObjectMapper json) {
		this.service = service;
		this.settings = settings;
		this.json = json;
	}

	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", required = false) String token,
			@RequestParam(name = "hub.challenge", required = false) String challenge) {
		boolean ok = "subscribe".equals(mode) && !settings.verifyToken().isEmpty()
			&& MessageDigest.isEqual(settings.verifyToken().getBytes(StandardCharsets.UTF_8),
				(token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
		return ok ? ResponseEntity.ok(challenge == null ? "" : challenge) : ResponseEntity.status(403).body("Forbidden");
	}

	@PostMapping(consumes = MediaType.ALL_VALUE)
	public ResponseEntity<Void> receive(@RequestBody byte[] body,
			@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
		if (!validSignature(body, signature)) {
			log.warn("WhatsApp webhook refused: missing or wrong signature");
			return ResponseEntity.status(401).build();
		}
		try {
			service.handleWebhook(json.readTree(body));
		} catch (RuntimeException e) {
			// Answer 200 anyway: Meta would resend the same broken call for days. It is logged.
			log.error("WhatsApp webhook could not be processed", e);
		}
		return ResponseEntity.ok().build();
	}

	boolean validSignature(byte[] body, String header) {
		if (settings.appSecret().isEmpty() || header == null || !header.startsWith("sha256=")) return false;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(body);
			byte[] given = HexFormat.of().parseHex(header.substring(7).trim());
			return MessageDigest.isEqual(expected, given);
		} catch (Exception e) {
			return false;
		}
	}
}
