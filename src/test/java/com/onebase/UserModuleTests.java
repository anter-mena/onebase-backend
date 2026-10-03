package com.onebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.List;
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
 * Roles and the Users module, against a real PostgreSQL.
 *
 * <p>Same configuration as {@link OnebaseBackendApplicationTests}, so Spring reuses
 * one application and one database for both. Each test makes its own people.
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
class UserModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	// ── Roles ───────────────────────────────────────────────────────────

	@Test
	void commercialIsRefusedAdminAreasButKeepsTheirOwn() throws Exception {
		newUser("com-roles@onebase.test", UserRole.COMMERCIAL);
		String token = login("com-roles@onebase.test");

		mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("You do not have permission to do that."));
		// Areas not built yet still answer by role: Admin-only by default, Commercial areas let through.
		mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
		mvc.perform(get("/api/clients").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("COMMERCIAL"));
	}

	@Test
	void adminSeesEveryoneButThemselves() throws Exception {
		newUser("admin-list@onebase.test", UserRole.ADMIN);
		newUser("listed@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/users").header("Authorization", "Bearer " + login("admin-list@onebase.test")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.email == 'listed@onebase.test')].role").value("COMMERCIAL"))
			.andExpect(jsonPath("$[?(@.email == 'admin-list@onebase.test')]").isEmpty());
	}

	// ── Invitations ─────────────────────────────────────────────────────

	@Test
	void inviteThenAcceptThenSignIn() throws Exception {
		String admin = adminToken("admin-invite@onebase.test");

		invite(admin, "COMMERCIAL", "new.person@onebase.test").andExpect(status().isCreated())
			.andExpect(jsonPath("$.invited[0].invitePending").value(true))
			.andExpect(jsonPath("$.invited[0].fullName").value("new.person"));
		String link = sentInvitationLink("new.person@onebase.test");
		assertThat(link).startsWith("https://app.test/accept-invite?token=");
		String token = link.substring(link.indexOf("token=") + 6);

		// Invited but not joined: cannot sign in, cannot reset a password.
		loginRequest("new.person@onebase.test", "whatever-123").andExpect(status().isUnauthorized());

		mvc.perform(post("/api/invitations/check").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("new.person@onebase.test"))
			.andExpect(jsonPath("$.role").value("COMMERCIAL"))
			.andExpect(jsonPath("$.expiresAt").isNotEmpty());

		accept(token, "New Person", "chosen-password").andExpect(status().isNoContent());
		accept(token, "Someone Else", "other-password").andExpect(status().isBadRequest()); // one use only

		String session = login("new.person@onebase.test", "chosen-password");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + session))
			.andExpect(jsonPath("$.fullName").value("New Person"));
	}

	@Test
	void inviteRefusesEmailsThatAlreadyHaveAnAccountAndInvitesNobody() throws Exception {
		String admin = adminToken("admin-dup@onebase.test");
		newUser("already@onebase.test", UserRole.COMMERCIAL);

		invite(admin, "COMMERCIAL", "fresh@onebase.test", "Already@OneBase.test")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Already in this workspace: already@onebase.test. Remove them and send again."));
		assertThat(users.findByEmail("fresh@onebase.test")).isEmpty();
	}

	@Test
	void commercialCannotInvite() throws Exception {
		newUser("com-invite@onebase.test", UserRole.COMMERCIAL);
		invite(login("com-invite@onebase.test"), "ADMIN", "sneaky@onebase.test").andExpect(status().isForbidden());
		assertThat(users.findByEmail("sneaky@onebase.test")).isEmpty();
	}

	@Test
	void resendReplacesTheLinkAndCancelRemovesTheInvite() throws Exception {
		String admin = adminToken("admin-resend@onebase.test");
		invite(admin, "COMMERCIAL", "typo@gmial.test").andExpect(status().isCreated());
		long id = users.findByEmail("typo@gmial.test").orElseThrow().getId();

		// Too soon after the first email.
		mvc.perform(post("/api/users/" + id + "/invitation/resend").header("Authorization", "Bearer " + admin))
			.andExpect(status().isTooManyRequests());

		mvc.perform(delete("/api/users/" + id + "/invitation").header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		assertThat(users.findById(id)).isEmpty();
	}

	@Test
	void cannotCancelSomeoneWhoAlreadyJoined() throws Exception {
		String admin = adminToken("admin-cancel@onebase.test");
		User joined = newUser("joined@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(delete("/api/users/" + joined.getId() + "/invitation").header("Authorization", "Bearer " + admin))
			.andExpect(status().isBadRequest());
	}

	// ── Activate / deactivate ───────────────────────────────────────────

	@Test
	void deactivatingSignsThePersonOutAtOnceAndReactivatingLetsThemBackIn() throws Exception {
		String admin = adminToken("admin-status@onebase.test");
		User commercial = newUser("status@onebase.test", UserRole.COMMERCIAL);
		String theirSession = login("status@onebase.test");

		setStatus(admin, commercial.getId(), false).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + theirSession)).andExpect(status().isUnauthorized());
		loginRequest("status@onebase.test", "user-password").andExpect(status().isForbidden());

		setStatus(admin, commercial.getId(), true).andExpect(status().isOk());
		login("status@onebase.test");
	}

	@Test
	void nobodyCanSwitchOffTheirOwnAccount() throws Exception {
		User admin = newUser("admin-self@onebase.test", UserRole.ADMIN);
		setStatus(login("admin-self@onebase.test"), admin.getId(), false)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("You can't switch off your own account."));
	}

	@Test
	void noAdminCanEverBeSwitchedOff() throws Exception {
		String admin = adminToken("admin-a@onebase.test");
		User otherAdmin = newUser("admin-b@onebase.test", UserRole.ADMIN);
		setStatus(admin, otherAdmin.getId(), false)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Admins can't be switched off."));
		assertThat(users.findById(otherAdmin.getId()).orElseThrow().isActive()).isTrue();
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private User newUser(String email, UserRole role) {
		return users.save(new User("Test User", email, encoder.encode("user-password"), role));
	}

	private String adminToken(String email) throws Exception {
		newUser(email, UserRole.ADMIN);
		return login(email);
	}

	private ResultActions loginRequest(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private String login(String email) throws Exception {
		return login(email, "user-password");
	}

	private String login(String email, String password) throws Exception {
		String body = loginRequest(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private ResultActions invite(String token, String role, String... emails) throws Exception {
		String list = String.join(",", List.of(emails).stream().map(e -> "\"" + e + "\"").toList());
		return mvc.perform(post("/api/users/invitations").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"emails\":[" + list + "],\"role\":\"" + role + "\",\"message\":\"Welcome!\"}"));
	}

	private String sentInvitationLink(String email) {
		ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
		verify(mail, atLeastOnce()).sendInvitation(eq(email), anyString(), any(), any(), link.capture());
		return link.getValue();
	}

	private ResultActions accept(String token, String name, String password) throws Exception {
		return mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"fullName\":\"" + name + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions setStatus(String token, long userId, boolean active) throws Exception {
		return mvc.perform(patch("/api/users/" + userId + "/status").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}"));
	}
}
