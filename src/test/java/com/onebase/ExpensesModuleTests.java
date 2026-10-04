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
 * Configuration → Expenses: plan costs and credits, perks, panel credit top-ups.
 * Shares the database with the other test classes, so each test touches its own rows.
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
class ExpensesModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	@Test
	void theRealCostsAndCreditsAreThereFromTheStart() throws Exception {
		String admin = token("exp-admin-1@onebase.test", UserRole.ADMIN);
		mvc.perform(get("/api/plans").header("Authorization", "Bearer " + admin))
			// 4 devices is left alone by the other tests.
			.andExpect(jsonPath("$[?(@.devices == 4)].cost").value(contains(1.66, 5.0, 10.0, 20.0)))
			.andExpect(jsonPath("$[?(@.devices == 4)].credits").value(contains(2, 6, 12, 24)));
	}

	@Test
	void savingCostsIsAllOrNothingAndLogged() throws Exception {
		String admin = token("exp-admin-2@onebase.test", UserRole.ADMIN);
		saveCosts(admin, "{\"devices\":3,\"months\":1,\"cost\":1.70,\"credits\":2},{\"devices\":3,\"months\":3,\"cost\":-1,\"credits\":6}")
			.andExpect(status().isBadRequest());
		saveCosts(admin, "{\"devices\":3,\"months\":1,\"cost\":1.70,\"credits\":3}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.devices == 3 && @.months == 1)].cost").value(contains(1.7)))
			.andExpect(jsonPath("$[?(@.devices == 3 && @.months == 1)].credits").value(contains(3)));
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetName == '3 devices · 1 month')].detail")
				.value(contains("Changed cost $1.66 → $1.70, credits 2 → 3")));
	}

	@Test
	void perksAreCreatedEditedSwitchedNeverDeleted() throws Exception {
		String admin = token("exp-admin-3@onebase.test", UserRole.ADMIN);
		mvc.perform(get("/api/perks").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.name == 'IBO Player')].cost").value(contains(2.0)));

		long id = idOf(perk(admin, null, "Extra Screen", "One more screen", "1.50").andExpect(status().isCreated()));
		perk(admin, null, "extra screen", null, "1").andExpect(status().isConflict());
		perk(admin, id, "Extra Screen", "One more screen", "1.75").andExpect(status().isOk()).andExpect(jsonPath("$.cost").value(1.75));
		mvc.perform(patch("/api/perks/" + id + "/status").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetType == 'PERK' && @.targetId == " + id + ")].action")
				.value(contains("DEACTIVATED", "UPDATED", "CREATED")));
	}

	@Test
	void topUpsAddCreditAndRecordWhatTheyCost() throws Exception {
		String admin = token("exp-admin-4@onebase.test", UserRole.ADMIN);
		long before = ((Number) JsonPath.read(mvc.perform(get("/api/credit").header("Authorization", "Bearer " + admin))
			.andReturn().getResponse().getContentAsString(), "$.remainingCredits")).longValue();

		topUp(admin, 120, "100.00", "First batch")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.remainingCredits").value(before + 120))
			.andExpect(jsonPath("$.lastTopup.credits").value(120))
			.andExpect(jsonPath("$.lastTopup.note").value("First batch"));
		topUp(admin, 0, "10", null).andExpect(status().isBadRequest());
		topUp(admin, 10, "-5", null).andExpect(status().isBadRequest());

		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetType == 'PANEL_CREDIT')].detail").value(contains("Added 120 credits for $100.00 (First batch)")));
	}

	@Test
	void commercialsSeeNoneOfIt() throws Exception {
		String commercial = token("exp-commercial@onebase.test", UserRole.COMMERCIAL);
		for (String url : new String[] { "/api/perks", "/api/credit", "/api/plans" }) {
			mvc.perform(get(url).header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		}
		topUp(commercial, 10, "5", null).andExpect(status().isForbidden());
		saveCosts(commercial, "{\"devices\":1,\"months\":1,\"cost\":1,\"credits\":1}").andExpect(status().isForbidden());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions saveCosts(String token, String changes) throws Exception {
		return mvc.perform(put("/api/plans/costs").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[" + changes + "]}"));
	}

	private ResultActions perk(String token, Long id, String name, String description, String cost) throws Exception {
		String body = "{\"name\":\"" + name + "\"" + (description == null ? "" : ",\"description\":\"" + description + "\"")
			+ ",\"cost\":" + cost + "}";
		var request = id == null ? post("/api/perks") : put("/api/perks/" + id);
		return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions topUp(String token, int credits, String amount, String note) throws Exception {
		String body = "{\"credits\":" + credits + ",\"amount\":" + amount + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
		return mvc.perform(post("/api/credit/topups").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(body));
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
