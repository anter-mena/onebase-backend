package com.onebase.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OneBase's own copy of WhatsApp files, against a real PostgreSQL; the Cloud API is
 * replaced. Same configuration as WhatsAppModuleTests, so Spring reuses that application.
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
class WhatsAppMediaModuleTests {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@Autowired WhatsAppConversationRepository conversations;
	@Autowired WhatsAppMessageRepository messages;
	@Autowired WhatsAppMediaRepository media;
	@Autowired WhatsAppMediaSweeper sweeper;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void aSentFileIsKeptAndServedWithoutMeta() throws Exception {
		webhook(inboundText("212600000061", "wamid.IN61"));
		long conversation = conversations.findByWaId("212600000061").orElseThrow().getId();
		String token = token("media-commercial-61@onebase.test", UserRole.COMMERCIAL);
		when(api.upload(any(), eq("image/png"), eq("photo.png"))).thenReturn("media-OUT61");
		when(api.sendMedia(eq("212600000061"), eq("image"), eq("media-OUT61"), any(), any())).thenReturn("wamid.OUT61");

		String body = mvc.perform(multipart("/api/whatsapp/conversations/" + conversation + "/files")
				.file(new MockMultipartFile("file", "photo.png", "image/png", PNG))
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SENT"))
			.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		assertThat(media.findById(id)).hasValueSatisfying(f -> {
			assertThat(f.getData()).isEqualTo(PNG);
			assertThat(f.getMime()).isEqualTo("image/png");
			assertThat(f.getSizeBytes()).isEqualTo(PNG.length);
		});
		mvc.perform(get("/api/whatsapp/messages/" + id + "/media").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Media-Type", "image/png"))
			.andExpect(content().bytes(PNG));
		verify(api, never()).download(anyString());
	}

	@Test
	void aReceivedFileIsKeptByTheSweeperThenServedFromOurCopy() throws Exception {
		webhook(inboundImage("212600000062", "wamid.IN62", "media-IN62"));
		long id = messages.findByWaMessageId("wamid.IN62").orElseThrow().getId();
		assertThat(media.existsById(id)).isFalse(); // not in the webhook: Meta wants its answer at once
		when(api.download("media-IN62")).thenReturn(new WhatsAppCloudApi.MediaFile(PNG, "image/jpeg"));

		// What the sweeper does each minute, for this message.
		assertThat(sweeper.fetch(messages.findById(id).orElseThrow(), Instant.now())).isTrue();
		clearInvocations(api);

		assertThat(media.findById(id)).hasValueSatisfying(f -> assertThat(f.getData()).isEqualTo(PNG));
		String token = token("media-admin-62@onebase.test", UserRole.ADMIN);
		mvc.perform(get("/api/whatsapp/messages/" + id + "/media").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(content().bytes(PNG));
		mvc.perform(get("/api/whatsapp/messages/" + id + "/media").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk());
		verify(api, never()).download(anyString());
	}

	@Test
	void aFileMetaHasDeletedIsAClear404AndNoLongerRetried() throws Exception {
		webhook(inboundImage("212600000063", "wamid.IN63", "media-IN63"));
		long id = messages.findByWaMessageId("wamid.IN63").orElseThrow().getId();
		when(api.download("media-IN63")).thenThrow(new WhatsAppCloudApi.MediaGoneException());
		String token = token("media-admin-63@onebase.test", UserRole.ADMIN);

		mvc.perform(get("/api/whatsapp/messages/" + id + "/media").header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("This file is no longer available: WhatsApp keeps files only for a limited time."));

		sweeper.fetch(messages.findById(id).orElseThrow(), Instant.now());
		assertThat(messages.findById(id).orElseThrow().getMediaFetchAttempts()).isEqualTo(WhatsAppMessage.MEDIA_GAVE_UP);
		assertThat(media.existsById(id)).isFalse();
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private void webhook(String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + signature))
			.andExpect(status().isOk());
	}

	private static String inboundText(String from, String wamid) {
		return inbound(from, wamid, "\"type\":\"text\",\"text\":{\"body\":\"Hello\"}");
	}

	private static String inboundImage(String from, String wamid, String mediaId) {
		return inbound(from, wamid, "\"type\":\"image\",\"image\":{\"id\":\"" + mediaId + "\",\"mime_type\":\"image/jpeg\"}");
	}

	private static String inbound(String from, String wamid, String content) {
		return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"111\"},"
			+ "\"contacts\":[{\"wa_id\":\"" + from + "\",\"profile\":{\"name\":\"Client " + from + "\"}}],"
			+ "\"messages\":[{\"from\":\"" + from + "\",\"id\":\"" + wamid + "\",\"timestamp\":\"" + Instant.now().getEpochSecond()
			+ "\"," + content + "}]}}]}]}";
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
