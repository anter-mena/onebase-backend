package com.onebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Account settings, changing a password, "last active", and the Action log,
 * against a real PostgreSQL.
 *
 * <p>Same configuration as the other test classes, so Spring reuses one
 * application and one database. The log is shared by every test, so each one
 * looks only at the lines about its own people.
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
class AccountAndActionLogTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	// ── Action log ──────────────────────────────────────────────────────

	@Test
	void signInIsLoggedAndANewBrowserIsCalledOut() throws Exception {
		User user = newUser("log-signin@onebase.test", UserRole.COMMERCIAL);
		login("log-signin@onebase.test", "Browser A");
		login("log-signin@onebase.test", "Browser A");
		login("log-signin@onebase.test", "Browser B");

		// Newest first: B is new, the second A is not, the first A was.
		logFor(user.getId())
			.andExpect(jsonPath("$[?(@.userId == " + user.getId() + ")].detail")
				.value(contains("Signed in from a new device", "Signed in", "Signed in from a new device")))
			.andExpect(jsonPath("$[?(@.userId == " + user.getId() + ")].ip").value(hasItem("203.0.113.9")))
			.andExpect(jsonPath("$[?(@.userId == " + user.getId() + ")].targetType").value(hasItem("WORKSPACE")));
	}

	@Test
	void onlyAdminsReadTheLog() throws Exception {
		newUser("log-commercial@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + login("log-commercial@onebase.test", "x")))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/action-log")).andExpect(status().isUnauthorized());
	}

	@Test
	void whatAdminsDoToAccountsIsLogged() throws Exception {
		User admin = newUser("log-admin@onebase.test", UserRole.ADMIN);
		String token = login("log-admin@onebase.test", "x");
		User commercial = newUser("log-target@onebase.test", UserRole.COMMERCIAL);

		mvc.perform(post("/api/users/invitations").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"emails\":[\"log-invited@onebase.test\"],\"role\":\"COMMERCIAL\"}"))
			.andExpect(status().isCreated());
		mvc.perform(patch("/api/users/" + commercial.getId() + "/status").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isOk());

		String byAdmin = "$[?(@.userId == " + admin.getId() + " && @.targetType == 'USER')]";
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath(byAdmin + ".action").value(contains("DEACTIVATED", "CREATED")))
			.andExpect(jsonPath(byAdmin + ".targetName").value(contains("Test User", "log-invited@onebase.test")))
			.andExpect(jsonPath(byAdmin + ".detail").value(contains("Account switched off and signed out everywhere", "Invited as Commercial")))
			.andExpect(jsonPath(byAdmin + ".actor").value(hasItem("Test User")));
	}

	// ── Account settings ────────────────────────────────────────────────

	@Test
	void settingsAreSavedAndLogged() throws Exception {
		User user = newUser("settings@onebase.test", UserRole.COMMERCIAL);
		String token = login("settings@onebase.test", "x");

		settings(token, "  New Name ", "Africa/Casablanca", "yyyy-MM-dd", false)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fullName").value("New Name"));
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
			.andExpect(jsonPath("$.fullName").value("New Name"))
			.andExpect(jsonPath("$.language").doesNotExist())
			.andExpect(jsonPath("$.timeZone").value("Africa/Casablanca"))
			.andExpect(jsonPath("$.dateFormat").value("yyyy-MM-dd"))
			.andExpect(jsonPath("$.notifyRenewals").value(false))
			.andExpect(jsonPath("$.email").value("settings@onebase.test"));

		settings(token, "New Name", "Mars/Olympus", "yyyy-MM-dd", true).andExpect(status().isBadRequest());
		settings(token, "New Name", "UTC", "whatever", true).andExpect(status().isBadRequest());
		settings(token, " ", "UTC", "yyyy-MM-dd", true).andExpect(status().isBadRequest());

		String admin = login(newUser("settings-admin@onebase.test", UserRole.ADMIN).getEmail(), "x");
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.userId == " + user.getId() + " && @.action == 'UPDATED')].detail")
				.value(contains("Changed their name from Test User to New Name, time zone, date format, email notifications")));
	}

	@Test
	void changingThePasswordStampsItAndLogsIt() throws Exception {
		User user = newUser("change-log@onebase.test", UserRole.COMMERCIAL);
		Instant before = users.findById(user.getId()).orElseThrow().getPasswordChangedAt();
		String token = login("change-log@onebase.test", "x");

		mvc.perform(post("/api/auth/password/change").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"user-password\",\"newPassword\":\"new-password-1\"}"))
			.andExpect(status().isOk());

		assertThat(users.findById(user.getId()).orElseThrow().getPasswordChangedAt()).isAfter(before);
		String admin = login(newUser("change-log-admin@onebase.test", UserRole.ADMIN).getEmail(), "x");
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.userId == " + user.getId() + " && @.action == 'UPDATED')].detail")
				.value(contains("Changed their password")));
	}

	// ── Last active ─────────────────────────────────────────────────────

	@Test
	void usingTheAppKeepsLastActiveFresh() throws Exception {
		User user = newUser("active@onebase.test", UserRole.COMMERCIAL);
		String token = login("active@onebase.test", "x");

		// Pretend the last sign of life was an hour ago.
		User stored = users.findById(user.getId()).orElseThrow();
		Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
		stored.setLastActiveAt(hourAgo);
		users.save(stored);

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		assertThat(users.findById(user.getId()).orElseThrow().getLastActiveAt()).isAfter(hourAgo.plusSeconds(3500));
	}

	// ── System status ───────────────────────────────────────────────────

	@Test
	void systemHealthIsForAdminsAndReportsEveryLayer() throws Exception {
		String admin = login(newUser("health-admin@onebase.test", UserRole.ADMIN).getEmail(), "x");
		String body = mvc.perform(get("/api/system/health").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			// No Docker proxy in tests: said so, rather than an empty list passed off as "nothing running".
			.andExpect(jsonPath("$.containers.available").value(false))
			.andExpect(jsonPath("$.containers.detail").isNotEmpty())
			.andExpect(jsonPath("$.database.tables[*].name").value(hasItem("users")))
			.andExpect(jsonPath("$.database.tables[*].name").value(hasItem("action_logs")))
			.andReturn().getResponse().getContentAsString();
		for (String figure : new String[] { "$.server.cores", "$.server.memoryTotal", "$.server.diskTotal",
				"$.backend.heapMax", "$.backend.totalRequests", "$.backend.pool.max", "$.database.sizeBytes" }) {
			assertThat(JsonPath.<Number>read(body, figure).longValue()).as(figure).isPositive();
		}

		String commercial = login(newUser("health-commercial@onebase.test", UserRole.COMMERCIAL).getEmail(), "x");
		mvc.perform(get("/api/system/health").header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		mvc.perform(get("/api/system/health")).andExpect(status().isUnauthorized());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private User newUser(String email, UserRole role) {
		return users.save(new User("Test User", email, encoder.encode("user-password"), role));
	}

	private String login(String email, String browser) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("User-Agent", browser)
				.header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
				.content("{\"email\":\"" + email + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private ResultActions logFor(long userId) throws Exception {
		String admin = login(newUser("reader-" + userId + "@onebase.test", UserRole.ADMIN).getEmail(), "reader");
		return mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
	}

	private ResultActions settings(String token, String name, String timeZone, String dateFormat,
			boolean renewals) throws Exception {
		return mvc.perform(patch("/api/auth/me").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"fullName":"%s","timeZone":"%s","dateFormat":"%s",
				 "notifyRenewals":%s,"notifyFailedPayments":true,"notifyWeeklyDigest":true}
				""".formatted(name, timeZone, dateFormat, renewals)));
	}
}
