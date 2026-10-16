package com.onebase.template;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.onebase.whatsapp.WhatsAppCloudApi;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Communication → Templates against a real PostgreSQL, and sending one from the
 * WhatsApp Inbox without Meta (the Cloud API is replaced). Same configuration as
 * WhatsAppModuleTests, so Spring reuses that application. Each test uses its own names.
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
class TemplateModuleTests {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D };
	private static final byte[] PDF = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void uploadCreateEditAndTheFileNothingUsesIsDeleted() throws Exception {
		String admin = token("tpl-admin-1@onebase.test", UserRole.ADMIN);
		long first = upload(admin, "promo.png", PNG);
		long second = upload(admin, "promo-2.png", PNG);

		// The builder's preview: the bytes, as what they are.
		mvc.perform(get("/api/templates/files/" + first).header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(content().bytes(PNG));

		String body = save(admin, null, "Trial ending", "EN", "EasyIPTV",
			"[{\"type\":\"TEXT\",\"text\":\"Hi! Your trial ends soon.\"},"
				+ "{\"type\":\"IMAGE\",\"fileId\":" + first + "},"
				+ "{\"type\":\"BUTTON\",\"buttonType\":\"QUICK_REPLY\",\"label\":\"Renew now\"}]")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Trial ending"))
			.andExpect(jsonPath("$.language").value("EN"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.blocks[1].file.name").value("promo.png"))
			.andExpect(jsonPath("$.blocks[1].file.mime").value("image/png"))
			.andExpect(jsonPath("$.blocks[2].label").value("Renew now"))
			.andExpect(jsonPath("$.blocks[2].value").isEmpty())
			.andExpect(jsonPath("$.summary").value("1 text · 1 photo · 1 button"))
			.andExpect(jsonPath("$.updatedByName").value("Test User"))
			.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		// Same name in the same language, whatever the capitals: refused. In French: fine.
		save(admin, null, "TRIAL ENDING", "EN", "", "[{\"type\":\"TEXT\",\"text\":\"Hi\"}]")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("A template named “Trial ending” already exists in English."));
		save(admin, null, "Trial ending", "FR", "", "[{\"type\":\"TEXT\",\"text\":\"Salut\"}]")
			.andExpect(status().isCreated());

		// A rule broken: 400 with the sentence. A PDF can't be the photo.
		long pdf = upload(admin, "guide.pdf", PDF);
		save(admin, id, "Trial ending", "EN", "", "[{\"type\":\"IMAGE\",\"fileId\":" + pdf + "}]")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("This file can't be used as the photo: use a JPG or PNG picture."));

		// Edit: the photo replaced. The old one is no longer used by anything, so it is gone.
		save(admin, id, "Trial ending", "EN", "EasyIPTV",
			"[{\"type\":\"TEXT\",\"text\":\"Hi! Your trial ends tomorrow.\"},{\"type\":\"IMAGE\",\"fileId\":" + second + "}]")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blocks[0].text").value("Hi! Your trial ends tomorrow."))
			.andExpect(jsonPath("$.blocks[1].file.id").value(second))
			.andExpect(jsonPath("$.blocks.length()").value(2));
		mvc.perform(get("/api/templates/files/" + first).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/templates/files/" + second).header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk());

		mvc.perform(get("/api/templates").param("q", "tomorrow").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(contains((int) id)));
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetType == 'TEMPLATE' && @.targetId == " + id + ")].action")
				.value(contains("UPDATED", "CREATED")))
			.andExpect(jsonPath("$[?(@.targetType == 'TEMPLATE' && @.targetId == " + id + " && @.action == 'UPDATED')].detail")
				.value(contains("Changed message")));
	}

	@Test
	void uploadsAreReadFromTheirBytes() throws Exception {
		String admin = token("tpl-admin-2@onebase.test", UserRole.ADMIN);
		mvc.perform(multipart("/api/templates/files")
				.file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", "<html>not a photo</html>".getBytes()))
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(
				"This file can't be sent: use a JPG or PNG picture, a PDF, or an MP3, OGG, AAC, M4A or AMR audio."));
		mvc.perform(multipart("/api/templates/files")
				.file(new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[TemplateRules.MAX_FILE_BYTES + 1]))
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Files can be 4 MB at most."));
		// Named .txt, but its bytes are a PDF: a PDF it is.
		mvc.perform(multipart("/api/templates/files").file(new MockMultipartFile("file", "notes.txt", "text/plain", PDF))
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mime").value("application/pdf"))
			.andExpect(jsonPath("$.name").value("notes.txt"))
			.andExpect(jsonPath("$.size").value(PDF.length));
	}

	@Test
	void switchingOffKeepsUpdatedAtAndHidesItFromTheInbox() throws Exception {
		String admin = token("tpl-admin-3@onebase.test", UserRole.ADMIN);
		String commercial = token("tpl-commercial-3@onebase.test", UserRole.COMMERCIAL);
		long file = upload(admin, "offer.pdf", PDF);
		String body = save(admin, null, "Offer PDF", "EN", "",
			"[{\"type\":\"TEXT\",\"text\":\"Our offer\"},{\"type\":\"DOCUMENT\",\"fileId\":" + file + "}]")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();
		String updatedAt = JsonPath.read(body, "$.updatedAt");

		// The Inbox (a Commercial too) sees it, and its file.
		mvc.perform(get("/api/whatsapp/templates").header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(hasItem((int) id)));
		mvc.perform(get("/api/whatsapp/templates/files/" + file).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/pdf"));

		mvc.perform(patch("/api/templates/" + id + "/status").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.updatedAt").value(updatedAt));

		mvc.perform(get("/api/templates").param("active", "false").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[*].id").value(hasItem((int) id)));
		mvc.perform(get("/api/templates").param("active", "true").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));
		mvc.perform(get("/api/whatsapp/templates").header("Authorization", "Bearer " + commercial))
			.andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));
		mvc.perform(get("/api/whatsapp/templates/files/" + file).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$[?(@.targetType == 'TEMPLATE' && @.targetId == " + id + ")].action")
				.value(contains("DEACTIVATED", "CREATED")));
	}

	@Test
	void templatesAreForAdminsOnly() throws Exception {
		String commercial = token("tpl-commercial-4@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/templates").header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		save(commercial, null, "Sneaky", "EN", "", "[{\"type\":\"TEXT\",\"text\":\"Hi\"}]").andExpect(status().isForbidden());
		mvc.perform(multipart("/api/templates/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
				.header("Authorization", "Bearer " + commercial))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/templates")).andExpect(status().isUnauthorized());
	}

	@Test
	void sendingFromTheInboxInsideTheWindowOnly() throws Exception {
		String admin = token("tpl-admin-5@onebase.test", UserRole.ADMIN);
		String commercial = token("tpl-commercial-5@onebase.test", UserRole.COMMERCIAL);
		String body = save(admin, null, "Renew prompt", "EN", "EasyIPTV",
			"[{\"type\":\"TEXT\",\"text\":\"Your plan ends soon.\"},{\"type\":\"BUTTON\",\"buttonType\":\"QUICK_REPLY\",\"label\":\"Renew now\"}]")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		long open = conversation("212600000051", Instant.now());
		when(api.sendInteractive(eq("212600000051"), anyMap())).thenReturn("wamid.TPL51");
		mvc.perform(post("/api/whatsapp/conversations/" + open + "/templates/" + id).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].type").value("TEXT"))
			.andExpect(jsonPath("$[0].status").value("SENT"))
			.andExpect(jsonPath("$[0].body").value("Your plan ends soon."))
			.andExpect(jsonPath("$[0].footer").value("EasyIPTV"))
			.andExpect(jsonPath("$[0].buttons.length()").value(1))
			.andExpect(jsonPath("$[0].buttons[0].type").value("QUICK_REPLY"))
			.andExpect(jsonPath("$[0].buttons[0].label").value("Renew now"))
			.andExpect(jsonPath("$[0].buttons[0].value").isEmpty());

		// A photo under the words, with a link: kept as a photo (downloadable), the link as its button.
		long photo = upload(admin, "offer.png", PNG);
		String withPhoto = save(admin, null, "Photo offer", "EN", "",
			"[{\"type\":\"TEXT\",\"text\":\"See our offer\"},{\"type\":\"IMAGE\",\"fileId\":" + photo + "},"
				+ "{\"type\":\"BUTTON\",\"buttonType\":\"URL\",\"label\":\"Open\",\"value\":\"https://x.test/o\"}]")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		when(api.upload(any(), eq("image/png"), eq("offer.png"))).thenReturn("media-51");
		when(api.sendInteractive(eq("212600000051"), anyMap())).thenReturn("wamid.TPL51B");
		mvc.perform(post("/api/whatsapp/conversations/" + open + "/templates/" + JsonPath.read(withPhoto, "$.id"))
				.header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].type").value("IMAGE"))
			.andExpect(jsonPath("$[0].hasMedia").value(true))
			.andExpect(jsonPath("$[0].mediaMime").value("image/png"))
			.andExpect(jsonPath("$[0].mediaFilename").value("offer.png"))
			.andExpect(jsonPath("$[0].body").value("See our offer"))
			.andExpect(jsonPath("$[0].footer").isEmpty())
			.andExpect(jsonPath("$[0].buttons[0].type").value("URL"))
			.andExpect(jsonPath("$[0].buttons[0].label").value("Open"))
			.andExpect(jsonPath("$[0].buttons[0].value").value("https://x.test/o"));
		// Older messages: no footer, no buttons.
		mvc.perform(get("/api/whatsapp/conversations/" + open + "/messages").header("Authorization", "Bearer " + commercial))
			.andExpect(jsonPath("$[0].footer").isEmpty())
			.andExpect(jsonPath("$[0].buttons.length()").value(0));

		long closed = conversation("212600000052", Instant.now().minusSeconds(25 * 3600));
		mvc.perform(post("/api/whatsapp/conversations/" + closed + "/templates/" + id).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("More than 24 hours since the client's last message: only a template can be sent now."));

		mvc.perform(patch("/api/templates/" + id + "/status").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/whatsapp/conversations/" + open + "/templates/" + id).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isBadRequest());
		verify(api, never()).sendInteractive(eq("212600000052"), any());
		verify(api, never()).sendText(eq("212600000051"), anyString());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private long upload(String token, String name, byte[] bytes) throws Exception {
		String body = mvc.perform(multipart("/api/templates/files")
				.file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private ResultActions save(String token, Long id, String name, String language, String footer, String blocks)
			throws Exception {
		String json = "{\"name\":\"" + name + "\",\"language\":\"" + language + "\",\"active\":true,\"footer\":\"" + footer
			+ "\",\"blocks\":" + blocks + "}";
		return mvc.perform((id == null ? post("/api/templates") : put("/api/templates/" + id))
			.header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** A conversation whose client last wrote at {@code at}, through the webhook as Meta would. */
	private long conversation(String from, Instant at) throws Exception {
		String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"111\"},"
			+ "\"contacts\":[{\"wa_id\":\"" + from + "\",\"profile\":{\"name\":\"Client " + from + "\"}}],"
			+ "\"messages\":[{\"from\":\"" + from + "\",\"id\":\"wamid.IN" + from + "\",\"timestamp\":\"" + at.getEpochSecond()
			+ "\",\"type\":\"text\",\"text\":{\"body\":\"Hello\"}}]}}]}]}";
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + signature))
			.andExpect(status().isOk());
		String admin = token("tpl-reader-" + from + "@onebase.test", UserRole.ADMIN);
		String list = mvc.perform(get("/api/whatsapp/conversations").param("q", from).header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(list, "$[0].id")).longValue();
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
