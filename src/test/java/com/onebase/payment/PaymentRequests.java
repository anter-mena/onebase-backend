package com.onebase.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Adding a payment in tests, as the window does: the payment and its proof (a payment has none without one). */
public final class PaymentRequests {

	private PaymentRequests() {
	}

	/** {@code json} is the payment, as the window sends it; a one-line PDF goes with it as the proof. */
	public static MockMultipartHttpServletRequestBuilder addPayment(long clientId, String json) {
		return multipart("/api/clients/" + clientId + "/payments")
			.file(new MockMultipartFile("payment", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8)))
			.file(new MockMultipartFile("proofs", "proof.pdf", "application/pdf", "%PDF-1.7 test proof".getBytes(StandardCharsets.UTF_8)));
	}
}
