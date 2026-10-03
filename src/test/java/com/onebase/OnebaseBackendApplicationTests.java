package com.onebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * The whole auth flow against a real PostgreSQL, through the real security rules.
 *
 * <p>Each test makes its own user, so they never depend on each other's state.
 * Email is mocked: the reset test reads the link the mail service was asked to send.
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
class OnebaseBackendApplicationTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	// ── Public / docs ────────────────────────────────────────────────────

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void swaggerNeedsTheDocsLogin() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/v3/api-docs").with(httpBasic("test-docs", "wrong"))).andExpect(status().isUnauthorized());
		mvc.perform(get("/v3/api-docs").with(httpBasic("test-docs", "test-docs-password"))).andExpect(status().isOk());
	}

	@Test
	void apiRefusesAnonymousCallsWithJsonAndNoBrowserPopup() throws Exception {
		mvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().doesNotExist("WWW-Authenticate"))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.message").value("Please sign in to continue."));
	}

	// ── Sign in / out ────────────────────────────────────────────────────

	@Test
	void bootstrapOwnerCanSignIn() throws Exception {
		String token = login("owner@onebase.test", "owner-password");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("owner@onebase.test"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
		newUser("same-answer@onebase.test", "right-password");
		loginRequest("same-answer@onebase.test", "wrong-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Invalid email or password."));
		loginRequest("nobody@onebase.test", "whatever-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Invalid email or password."));
	}

	@Test
	void emailIsCaseInsensitive() throws Exception {
		newUser("mixed@onebase.test", "right-password");
		login("  Mixed@OneBase.TEST ", "right-password");
	}

	@Test
	void logoutEndsTheSessionAtOnce() throws Exception {
		newUser("logout@onebase.test", "right-password");
		String token = login("logout@onebase.test", "right-password");

		mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}

	@Test
	void fiveWrongPasswordsLockTheAccount() throws Exception {
		newUser("lock@onebase.test", "right-password");
		for (int i = 0; i < 5; i++) {
			loginRequest("lock@onebase.test", "wrong-password").andExpect(status().isUnauthorized());
		}
		// Locked: even the right password is refused now.
		loginRequest("lock@onebase.test", "right-password").andExpect(status().isTooManyRequests());
	}

	@Test
	void switchedOffUserCannotSignIn() throws Exception {
		User user = newUser("off@onebase.test", "right-password");
		String token = login("off@onebase.test", "right-password");

		// Switched off after signing in: the open session stops working too.
		setActive(user, false);
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		loginRequest("off@onebase.test", "right-password").andExpect(status().isForbidden());
	}

	@Test
	void tamperedTokenIsRefused() throws Exception {
		newUser("tamper@onebase.test", "right-password");
		String token = login("tamper@onebase.test", "right-password");
		String tampered = token.substring(0, token.length() - 3) + (token.endsWith("aaa") ? "bbb" : "aaa");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
	}

	// ── Passwords ────────────────────────────────────────────────────────

	@Test
	void forgotPasswordAnswersTheSameForUnknownEmails() throws Exception {
		String known = forgot("known@onebase.test", true);
		String unknown = forgot("unknown@onebase.test", false);
		assertThat(known).isEqualTo(unknown);
	}

	@Test
	void resetLinkSetsANewPasswordOnceAndSignsOutEverywhere() throws Exception {
		newUser("reset@onebase.test", "old-password");
		String oldSession = login("reset@onebase.test", "old-password");

		forgot("reset@onebase.test", true);
		ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
		verify(mail).sendPasswordReset(eq("reset@onebase.test"), link.capture());
		assertThat(link.getValue()).startsWith("https://app.test/reset-password?token=");
		String token = link.getValue().substring(link.getValue().indexOf("token=") + 6);

		checkReset(token).andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").isNotEmpty());
		reset(token, "new-password").andExpect(status().isOk());
		reset(token, "another-password").andExpect(status().isBadRequest()); // one use only
		checkReset(token).andExpect(status().isBadRequest());
		checkReset("not-a-real-token").andExpect(status().isBadRequest());

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldSession)).andExpect(status().isUnauthorized());
		loginRequest("reset@onebase.test", "old-password").andExpect(status().isUnauthorized());
		login("reset@onebase.test", "new-password");
	}

	@Test
	void changePasswordKeepsThisDeviceAndSignsOutTheOthers() throws Exception {
		newUser("change@onebase.test", "old-password");
		String here = login("change@onebase.test", "old-password");
		String elsewhere = login("change@onebase.test", "old-password");

		mvc.perform(post("/api/auth/password/change").header("Authorization", "Bearer " + here)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\"}"))
			.andExpect(status().isOk());

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + here)).andExpect(status().isOk());
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + elsewhere)).andExpect(status().isUnauthorized());
	}

	@Test
	void shortPasswordsAreRefusedWithAFieldMessage() throws Exception {
		reset("anything", "short")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.newPassword").value("Use between 8 and 72 characters."));
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private User newUser(String email, String password) {
		return users.save(new User("Test User", email, encoder.encode(password), UserRole.COMMERCIAL));
	}

	private void setActive(User user, boolean active) {
		users.findById(user.getId()).ifPresent(u -> {
			u.setActive(active);
			users.save(u);
		});
	}

	private ResultActions loginRequest(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private String login(String email, String password) throws Exception {
		String body = loginRequest(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String forgot(String email, boolean createUserFirst) throws Exception {
		if (createUserFirst && users.findByEmail(email).isEmpty()) {
			newUser(email, "some-password");
		}
		return mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()
			.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
	}

	private ResultActions checkReset(String token) throws Exception {
		return mvc.perform(post("/api/auth/password/reset/check").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"));
	}

	private ResultActions reset(String token, String newPassword) throws Exception {
		return mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"));
	}
}
