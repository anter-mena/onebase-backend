package com.onebase.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.TestcontainersConfiguration;
import com.onebase.actionlog.ActionLogRepository;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import com.onebase.whatsapp.WhatsAppCloudApi;
import com.onebase.whatsapp.WhatsAppConversationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Clients: the client WhatsApp makes (the only way one is made), one client per
 * number, edit / note / delete, and who may do what.
 */
@SpringBootTest(properties = {
	"onebase.docs.username=test-docs",
	"onebase.docs.password=test-docs-password",
	"onebase.jwt.secret=test-secret-test-secret-test-secret-123",
	"onebase.bootstrap.owner-email=owner@onebase.test",
	"onebase.bootstrap.owner-password=owner-password",
	"onebase.frontend-url=https://app.test",
	"onebase.whatsapp.token=test-token",
	"onebase.whatsapp.phone-number-id=111",
	"onebase.whatsapp.app-secret=test-app-secret",
	"onebase.whatsapp.verify-token=verify-me",
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClientModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@Autowired ClientRepository clients;
	@Autowired WhatsAppConversationRepository conversations;
	@Autowired ActionLogRepository logs;
	@Autowired TrialCallbackJob trialJob;
	@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void phoneNumbersHaveOneShapeAndACountry() {
		assertThat(PhoneNumbers.normalize("+212 6 12-34 56 78")).isEqualTo("+212612345678");
		assertThat(PhoneNumbers.normalize("00212612345678")).isEqualTo("+212612345678");
		assertThat(PhoneNumbers.normalize("212612345678")).isEqualTo("+212612345678");
		assertThat(PhoneNumbers.normalize("  ")).isNull();
		assertThat(PhoneNumbers.country("+212612345678")).isEqualTo("MA");
		assertThat(PhoneNumbers.country("+33612345678")).isEqualTo("FR");
		assertThat(PhoneNumbers.country("+14165550123")).isEqualTo("CA");
		assertThat(PhoneNumbers.fromWhatsApp("15551954635")).isEqualTo("+15551954635");
		assertThat(PhoneNumbers.fromWhatsApp("abc")).isNull();
	}

	@Test
	void aNumberThatWritesBecomesANewClientOnce() throws Exception {
		webhook(inbound("212611110001", "Karim", "wamid.C1", "Hi"));
		Client client = clients.findByPhone("+212611110001").orElseThrow();
		assertThat(client.getStatus()).isEqualTo(Client.Status.NEW);
		assertThat(client.getSource()).isEqualTo(Client.Source.WHATSAPP);
		assertThat(client.getUsername()).isEqualTo("Karim");
		assertThat(client.getFullName()).isNull();
		assertThat(client.getBrandId()).isNull();
		assertThat(client.getCountry()).isEqualTo("MA");
		assertThat(conversations.findByWaId("212611110001").orElseThrow().getClientId()).isEqualTo(client.getId());

		// Again, under a new profile name: the same client, the WhatsApp name follows.
		webhook(inbound("212611110001", "Karim B.", "wamid.C2", "Still there?"));
		assertThat(clients.findAll().stream().filter(c -> "+212611110001".equals(c.getPhone())).count()).isEqualTo(1);
		assertThat(clients.findById(client.getId()).orElseThrow().getUsername()).isEqualTo("Karim B.");

		String commercial = token("cl-wa-commercial@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/clients/" + client.getId()).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Karim B."))
			.andExpect(jsonPath("$.phone").value("+212611110001"))
			.andExpect(jsonPath("$.country").value("MA"))
			.andExpect(jsonPath("$.conversationId").isNumber());
	}

	@Test
	void theTeamCompletesClientsButCannotAddThemOnePerNumber() throws Exception {
		String commercial = token("cl-commercial@onebase.test", UserRole.COMMERCIAL);

		// No Add client: only WhatsApp makes clients.
		mvc.perform(post("/api/clients").header("Authorization", "Bearer " + commercial).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Sara Amrani\",\"phone\":\"+212612345679\",\"status\":\"NEW\"}"))
			.andExpect(status().isMethodNotAllowed());

		webhook(inbound("212611110004", "Sara", "wamid.E1", "Hi"));
		webhook(inbound("212611110005", "Other", "wamid.E2", "Hi"));
		long id = clients.findByPhone("+212611110004").orElseThrow().getId();

		mvc.perform(put("/api/clients/" + id).header("Authorization", "Bearer " + commercial).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Sara Amrani\",\"phone\":\"0612345679\",\"status\":\"NEW\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(PhoneNumbers.NEEDS_COUNTRY_CODE));
		// Another client's number, typed another way, is refused.
		mvc.perform(put("/api/clients/" + id).header("Authorization", "Bearer " + commercial).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"Sara Amrani\",\"phone\":\"00212611110005\",\"status\":\"NEW\"}"))
			.andExpect(status().isConflict());

		// Edit: a French number now, and Active.
		mvc.perform(put("/api/clients/" + id).header("Authorization", "Bearer " + commercial).contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"  Sara   Amrani \",\"email\":\"Sara@Example.com\",\"phone\":\"+33 6 12 34 56 78\",\"status\":\"TRIAL\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Sara Amrani"))
			.andExpect(jsonPath("$.email").value("sara@example.com"))
			.andExpect(jsonPath("$.phone").value("+33612345678"))
			.andExpect(jsonPath("$.country").value("FR"))
			.andExpect(jsonPath("$.status").value("TRIAL"));

		mvc.perform(put("/api/clients/" + id + "/note").header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  Calls after 6pm. \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.note").value("Calls after 6pm."));

		List<String> details = logs.findAll().stream().filter(l -> Long.valueOf(id).equals(l.getTargetId()))
			.map(l -> l.getDetail()).toList();
		assertThat(details).contains("Changed the note");
		assertThat(details).anyMatch(d -> d.contains("full name") && d.contains("phone") && d.contains("status from New to Trial"));
	}

	@Test
	void onlyTrialPendingAndDropAreChosenByHandAndTrialsTurnIntoCallbacks() throws Exception {
		String commercial = token("cl-status@onebase.test", UserRole.COMMERCIAL);
		webhook(inbound("212611110006", "Tia", "wamid.S1", "Hi"));
		Client client = clients.findByPhone("+212611110006").orElseThrow();
		String phone = "\"phone\":\"+212611110006\"";
		for (String automatic : new String[] { "ACTIVE", "INACTIVE", "CALLBACK" }) {
			mvc.perform(put("/api/clients/" + client.getId()).header("Authorization", "Bearer " + commercial)
					.contentType(MediaType.APPLICATION_JSON).content("{" + phone + ",\"status\":\"" + automatic + "\"}"))
				.andExpect(status().isBadRequest());
		}
		// Keeping an automatic status while editing something else is fine.
		mvc.perform(put("/api/clients/" + client.getId()).header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content("{" + phone + ",\"fullName\":\"Tia B\",\"status\":\"NEW\"}"))
			.andExpect(status().isOk());
		mvc.perform(put("/api/clients/" + client.getId()).header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content("{" + phone + ",\"fullName\":\"Tia B\",\"status\":\"TRIAL\"}"))
			.andExpect(status().isOk());

		// Not a day yet: still Trial.
		trialJob.run();
		assertThat(clients.findById(client.getId()).orElseThrow().getStatus()).isEqualTo(Client.Status.TRIAL);
		// A day and a bit later: Callback, and it shows in Renewals.
		jdbc.update("update clients set status_changed_at = now() - interval '25 hours' where id = ?", client.getId());
		trialJob.run();
		assertThat(clients.findById(client.getId()).orElseThrow().getStatus()).isEqualTo(Client.Status.CALLBACK);
		mvc.perform(get("/api/renewals").header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.client.id == " + client.getId() + ")].group").value("CALLBACK"));
	}

	@Test
	void onlyAdminsDeleteAndADeletedClientWhoWritesComesBack() throws Exception {
		webhook(inbound("212611110002", "Nora", "wamid.D1", "Hello"));
		long id = clients.findByPhone("+212611110002").orElseThrow().getId();

		String commercial = token("cl-del-commercial@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(delete("/api/clients/" + id).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isForbidden());

		String admin = token("cl-del-admin@onebase.test", UserRole.ADMIN);
		mvc.perform(delete("/api/clients/" + id).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/clients/" + id).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
		String list = mvc.perform(get("/api/clients").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("+212611110002");

		webhook(inbound("212611110002", "Nora", "wamid.D2", "Me again"));
		mvc.perform(get("/api/clients/" + id).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private void webhook(String body) throws Exception {
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + hmac(body)))
			.andExpect(status().isOk());
	}

	private static String hmac(String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
	}

	private static String inbound(String from, String name, String wamid, String text) {
		return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"111\"},"
			+ "\"contacts\":[{\"wa_id\":\"" + from + "\",\"profile\":{\"name\":\"" + name + "\"}}],"
			+ "\"messages\":[{\"from\":\"" + from + "\",\"id\":\"" + wamid + "\",\"timestamp\":\"" + Instant.now().getEpochSecond()
			+ "\",\"type\":\"text\",\"text\":{\"body\":\"" + text + "\"}}]}}]}]}";
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
