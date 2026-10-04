package com.onebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.mail.MailService;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
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
 * Configuration → Brands, against a real PostgreSQL. Same configuration as the
 * other test classes, so Spring reuses one application and one database. Each
 * test uses its own domains.
 *
 * <p>No test reads a real website: the lookup's reading is tested in
 * BrandSafetyTests, and here only its refusals (which never go online).
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
class BrandModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@MockitoBean MailService mail;

	@Test
	void addWithALogoThenTheSameSiteIsRefused() throws Exception {
		String admin = token("brand-admin-1@onebase.test", UserRole.ADMIN);
		String body = save(admin, null, "https://www.alpha-brand.test/en", "Alpha",
			"{\"instagram\":\"instagram.com/alpha/\",\"x\":\"https://twitter.com/alpha\"}", dataUrl(600, 300))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.domain").value("alpha-brand.test"))
			.andExpect(jsonPath("$.websiteUrl").value("https://www.alpha-brand.test"))
			.andExpect(jsonPath("$.socials.instagram").value("https://www.instagram.com/alpha"))
			.andExpect(jsonPath("$.socials.x").value("https://x.com/alpha"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.clients").value(0))
			.andReturn().getResponse().getContentAsString();

		// The logo we stored is a small PNG, served publicly, never as a page.
		String logoUrl = JsonPath.read(body, "$.logoUrl");
		byte[] png = mvc.perform(get(logoUrl))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(128);

		// Same site, written differently: refused, naming the brand that has it.
		save(admin, null, "alpha-brand.test", "Alpha again", "{}", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Alpha already uses alpha-brand.test."));
	}

	@Test
	void editSaysWhatChangedAndSwitchingOffDeletesNothing() throws Exception {
		User admin = user("brand-admin-2@onebase.test", UserRole.ADMIN);
		String token = login(admin.getEmail());
		long id = idOf(save(token, null, "beta-brand.test", "Beta", "{}", null).andExpect(status().isCreated()));

		save(token, id, "beta-brand.test", "Beta Shop", "{\"tiktok\":\"https://www.tiktok.com/@beta\"}", dataUrl(64, 64))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Beta Shop"))
			.andExpect(jsonPath("$.logoUrl").isNotEmpty());

		mvc.perform(patch("/api/brands/" + id + "/status").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		// Still listed: switched off, not deleted.
		mvc.perform(get("/api/brands").header("Authorization", "Bearer " + token))
			.andExpect(jsonPath("$[?(@.id == " + id + ")].active").value(contains(false)));

		mvc.perform(get("/api/action-log").header("Authorization", "Bearer " + token))
			.andExpect(jsonPath("$[?(@.targetType == 'BRAND' && @.targetId == " + id + ")].action")
				.value(contains("DEACTIVATED", "UPDATED", "CREATED")))
			.andExpect(jsonPath("$[?(@.targetType == 'BRAND' && @.targetId == " + id + " && @.action == 'UPDATED')].detail")
				.value(contains("Changed name from Beta to Beta Shop, TikTok, logo")));
	}

	@Test
	void commercialsReadButNeverChange() throws Exception {
		String admin = token("brand-admin-3@onebase.test", UserRole.ADMIN);
		long id = idOf(save(admin, null, "gamma-brand.test", "Gamma", "{}", null).andExpect(status().isCreated()));
		String commercial = token("brand-commercial@onebase.test", UserRole.COMMERCIAL);

		mvc.perform(get("/api/brands").header("Authorization", "Bearer " + commercial)).andExpect(status().isOk());
		save(commercial, null, "delta-brand.test", "Delta", "{}", null).andExpect(status().isForbidden());
		save(commercial, id, "gamma-brand.test", "Renamed", "{}", null).andExpect(status().isForbidden());
		mvc.perform(patch("/api/brands/" + id + "/status").header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
			.andExpect(status().isForbidden());
		lookup(commercial, "nike.com").andExpect(status().isForbidden());
		mvc.perform(get("/api/brands")).andExpect(status().isUnauthorized());
	}

	@Test
	void theLookupRefusesInternalAddresses() throws Exception {
		String admin = token("brand-admin-4@onebase.test", UserRole.ADMIN);
		for (String url : new String[] { "http://localhost:8080", "http://127.0.0.1", "http://169.254.169.254",
				"http://10.0.0.1", "http://docker-socket-proxy:2375", "ftp://example.com" }) {
			lookup(admin, url).andExpect(status().isBadRequest());
		}
	}

	@Test
	void badInputIsRefusedWithASentence() throws Exception {
		String admin = token("brand-admin-5@onebase.test", UserRole.ADMIN);
		save(admin, null, "epsilon-brand.test", "Epsilon", "{\"instagram\":\"https://evil.test/epsilon\"}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("The Instagram link is not an account on Instagram."));
		save(admin, null, "epsilon-brand.test", "Epsilon", "{}",
				"data:image/png;base64," + Base64.getEncoder().encodeToString("<svg onload=alert(1)>".getBytes()))
			.andExpect(status().isBadRequest());
		save(admin, null, "not a website", "Epsilon", "{}", null).andExpect(status().isBadRequest());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions save(String token, Long id, String website, String name, String socialsJson, String logo)
			throws Exception {
		String body = "{\"websiteUrl\":\"" + website + "\",\"name\":\"" + name + "\",\"socials\":" + socialsJson
			+ (logo == null ? "" : ",\"logo\":\"" + logo + "\"") + "}";
		var request = id == null ? post("/api/brands") : put("/api/brands/" + id);
		return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions lookup(String token, String url) throws Exception {
		return mvc.perform(post("/api/brands/lookup").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	private User user(String email, UserRole role) {
		return users.save(new User("Test User", email, encoder.encode("user-password"), role));
	}

	private String token(String email, UserRole role) throws Exception {
		return login(user(email, role).getEmail());
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String dataUrl(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
	}
}
