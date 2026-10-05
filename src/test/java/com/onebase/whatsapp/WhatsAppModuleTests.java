package com.onebase.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.TestcontainersConfiguration;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
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
 * WhatsApp without Meta: the webhook (verify, signature, messages, statuses), the
 * conversations, and the 24-hour rule. The Cloud API itself is replaced.
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
class WhatsAppModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@Autowired WhatsAppConversationRepository conversations;
	@Autowired WhatsAppMessageRepository messages;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void metaChecksTheAddressWithTheVerifyToken() throws Exception {
		mvc.perform(get("/api/whatsapp/webhook").param("hub.mode", "subscribe").param("hub.verify_token", "verify-me")
				.param("hub.challenge", "12345"))
			.andExpect(status().isOk()).andExpect(content().string("12345"));
		mvc.perform(get("/api/whatsapp/webhook").param("hub.mode", "subscribe").param("hub.verify_token", "wrong")
				.param("hub.challenge", "12345"))
			.andExpect(status().isForbidden());
	}

	@Test
	void onlySignedCallsAreTakenAndEachMessageIsSavedOnce() throws Exception {
		String body = inbound("212600000001", "Jane", "wamid.IN1", "Hello, my code?");
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + "00".repeat(32)))
			.andExpect(status().isUnauthorized());

		webhook(body);
		webhook(body); // Meta resends: still one message.

		WhatsAppConversation c = conversations.findByWaId("212600000001").orElseThrow();
		assertThat(c.getContactName()).isEqualTo("Jane");
		assertThat(c.getUnreadCount()).isEqualTo(1);
		assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(c.getId())).hasSize(1);
	}

	@Test
	void aCommercialReadsAndAnswersAndTheTicksFollowMeta() throws Exception {
		webhook(inbound("212600000002", "Omar", "wamid.IN2", "Is my line active?"));
		long id = conversations.findByWaId("212600000002").orElseThrow().getId();
		String commercial = token("wa-commercial@onebase.test", UserRole.COMMERCIAL);

		mvc.perform(get("/api/whatsapp/conversations").param("q", "omar").header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Omar"))
			.andExpect(jsonPath("$[0].unread").value(1))
			.andExpect(jsonPath("$[0].windowOpen").value(true));
		mvc.perform(get("/api/whatsapp/conversations/" + id + "/messages").param("markRead", "true")
				.header("Authorization", "Bearer " + commercial))
			.andExpect(jsonPath("$[0].body").value("Is my line active?"));
		assertThat(conversations.findById(id).orElseThrow().getUnreadCount()).isZero();

		when(api.sendText(eq("212600000002"), anyString())).thenReturn("wamid.OUT2");
		mvc.perform(post("/api/whatsapp/conversations/" + id + "/messages").header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Yes, until 12 Nov.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SENT"));

		webhook(statusUpdate("wamid.OUT2", "read"));
		webhook(statusUpdate("wamid.OUT2", "delivered")); // late: does not undo "read"
		assertThat(messages.findByWaMessageId("wamid.OUT2").orElseThrow().getStatus()).isEqualTo(WhatsAppMessage.Status.READ);
	}

	@Test
	void after24HoursOnlyTheTemplateGoesOut() throws Exception {
		WhatsAppConversation old = new WhatsAppConversation("212600000003", "Lina");
		old.received("Lina", "Hi", Instant.now().minusSeconds(25 * 3600));
		long id = conversations.save(old).getId();
		String admin = token("wa-admin@onebase.test", UserRole.ADMIN);

		mvc.perform(post("/api/whatsapp/conversations/" + id + "/messages").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hello again\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only a template")));

		when(api.sendTemplate("212600000003", "hello_world", "en_US")).thenReturn("wamid.T3");
		mvc.perform(post("/api/whatsapp/conversations/" + id + "/template").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("TEMPLATE"));
	}

	@Test
	void signedOutPeopleCannotReadConversations() throws Exception {
		mvc.perform(get("/api/whatsapp/conversations")).andExpect(status().isUnauthorized());
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

	private static String statusUpdate(String wamid, String status) {
		return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"metadata\":{\"phone_number_id\":\"111\"},\"statuses\":[{\"id\":\"" + wamid + "\",\"status\":\"" + status
			+ "\",\"timestamp\":\"" + Instant.now().getEpochSecond() + "\"}]}}]}]}";
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
