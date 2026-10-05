package com.onebase;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Configuration → Payment methods, against a real PostgreSQL. The database is
 * shared with the other test classes, so each test uses its own names.
 */
@SpringBootTest(properties = {
	"onebase.docs.username=test-docs",
	"onebase.docs.password=test-docs-password",
	"onebase.jwt.secret=test-secret-test-secret-test-secret-123",
	"onebase.bootstrap.owner-email=owner@onebase.test",
	"onebase.bootstrap.owner-password=owner-password",
	"onebase.frontend-url=https://app.test",
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentMethodModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	@Test
	void addStartsAtZeroAndTheSameNameIsRefused() throws Exception {
		String admin = token("pm-admin-1@onebase.test", UserRole.ADMIN);
		save(admin, null, "PAYPAL", "  Alpha   PayPal ", "Admin User", "BOTH", "Add your client number", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Alpha PayPal"))
			.andExpect(jsonPath("$.balance").value(0.0))
			.andExpect(jsonPath("$.active").value(true));

		save(admin, null, "DEBIT_CARD", "alpha paypal", "Someone", "VISA", null, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("A method called Alpha PayPal already exists."));
	}

	@Test
	void editAndSwitchAreLoggedAndNothingIsDeleted() throws Exception {
		String admin = token("pm-admin-2@onebase.test", UserRole.ADMIN);
		long id = idOf(save(admin, null, "INTERAC", "Beta Interac", "Admin User", "BOTH", null, null).andExpect(status().isCreated()));

		// The form changes the name, the network and the status at once.
		save(admin, id, "INTERAC", "Beta e-Transfer", "Admin User", "VISA", "Send to pay@example.com", false)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.instructions").value("Send to pay@example.com"));
		mvc.perform(patch("/api/payment-methods/" + id + "/status").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetType == 'PAYMENT_METHOD' && @.targetId == " + id + ")].action")
				.value(contains("ACTIVATED", "DEACTIVATED", "UPDATED", "CREATED")))
			.andExpect(jsonPath("$[?(@.targetType == 'PAYMENT_METHOD' && @.targetId == " + id + " && @.action == 'UPDATED')].detail")
				.value(contains("Changed name from Beta Interac to Beta e-Transfer, card network, instructions")));
		mvc.perform(get("/api/payment-methods/" + id).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
	}

	@Test
	void badInputIsRefusedWithASentence() throws Exception {
		String admin = token("pm-admin-3@onebase.test", UserRole.ADMIN);
		save(admin, null, "PAYPAL", " ", "Admin User", "BOTH", null, null).andExpect(status().isBadRequest());
		save(admin, null, "VENMO", "Gamma", "Admin User", "BOTH", null, null).andExpect(status().isBadRequest());
		mvc.perform(get("/api/payment-methods/999999").header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
	}

	@Test
	void commercialsSeeNothing() throws Exception {
		String commercial = token("pm-commercial@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/payment-methods").header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		save(commercial, null, "PAYPAL", "Delta", "Someone", "BOTH", null, null).andExpect(status().isForbidden());
		mvc.perform(get("/api/payment-methods")).andExpect(status().isUnauthorized());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions save(String token, Long id, String provider, String name, String holder, String network,
			String instructions, Boolean active) throws Exception {
		String body = "{\"provider\":\"" + provider + "\",\"name\":\"" + name + "\",\"holder\":\"" + holder
			+ "\",\"cardNetwork\":\"" + network + "\""
			+ (instructions == null ? "" : ",\"instructions\":\"" + instructions + "\"")
			+ (active == null ? "" : ",\"active\":" + active) + "}";
		var request = id == null ? post("/api/payment-methods") : put("/api/payment-methods/" + id);
		return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
