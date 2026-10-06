package com.onebase;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Configuration → Subscriptions, against a real PostgreSQL. The database is
 * shared with the other test classes, so each test changes its own plans only.
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
class PlanModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	@Test
	void theSixteenRealPricesAreThereFromTheStart() throws Exception {
		String admin = token("plans-admin-1@onebase.test", UserRole.ADMIN);
		mvc.perform(get("/api/plans").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(16)))
			// Untouched by the other tests: 4 devices.
			.andExpect(jsonPath("$[?(@.devices == 4)].price").value(contains(36.99, 65.99, 94.99, 144.99)));
	}

	@Test
	void savingChangesThePricesAndLogsEachOne() throws Exception {
		String admin = token("plans-admin-2@onebase.test", UserRole.ADMIN);
		save(admin, "{\"devices\":1,\"months\":1,\"price\":15.49},{\"devices\":1,\"months\":3,\"price\":21.99}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.devices == 1 && @.months == 1)].price").value(contains(15.49)));

		// Only the price that really changed is logged.
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetName == '1 device · 1 month')].detail").value(contains("Price changed from $14.99 to $15.49")));
	}

	@Test
	void oneBadPriceAndNothingIsSaved() throws Exception {
		String admin = token("plans-admin-3@onebase.test", UserRole.ADMIN);
		save(admin, "{\"devices\":2,\"months\":1,\"price\":19.99},{\"devices\":2,\"months\":3,\"price\":0}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("The price of 2 devices · 3 months must be above $0."));
		save(admin, "{\"devices\":2,\"months\":1,\"price\":19.999}").andExpect(status().isBadRequest());
		save(admin, "{\"devices\":5,\"months\":1,\"price\":19.99}").andExpect(status().isBadRequest());
		save(admin, "{\"devices\":2,\"months\":2,\"price\":19.99}").andExpect(status().isBadRequest());
		// The good change in the first request was not kept either.
		mvc.perform(get("/api/plans").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.devices == 2 && @.months == 1)].price").value(contains(21.99)));
	}

	@Test
	void commercialsSeeButCanNotChangePrices() throws Exception {
		String commercial = token("plans-commercial@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/plans").header("Authorization", "Bearer " + commercial)).andExpect(status().isOk());
		save(commercial, "{\"devices\":3,\"months\":1,\"price\":1}").andExpect(status().isForbidden());
		mvc.perform(get("/api/plans")).andExpect(status().isUnauthorized());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions save(String token, String changes) throws Exception {
		return mvc.perform(put("/api/plans/prices").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[" + changes + "]}"));
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
