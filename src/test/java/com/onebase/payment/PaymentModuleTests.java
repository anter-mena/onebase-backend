package com.onebase.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.TestcontainersConfiguration;
import com.onebase.client.Client;
import com.onebase.client.ClientRepository;
import com.onebase.mail.MailService;
import com.onebase.perk.PerkRepository;
import com.onebase.plan.Plan;
import com.onebase.plan.PlanRepository;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import com.onebase.whatsapp.WhatsAppCloudApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Payments: priced from Configuration, a private price, perks, the dates a
 * renewal gets, panel credit and method balances, the client's totals, and
 * delete (Admins).
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
	"onebase.timezone=America/Toronto",
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@Autowired ClientRepository clients;
	@Autowired PlanRepository plans;
	@Autowired PerkRepository perks;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void aPaymentIsPricedFromConfigurationAndMovesEverything() throws Exception {
		String commercial = token("pay-commercial@onebase.test", UserRole.COMMERCIAL);
		String admin = token("pay-admin@onebase.test", UserRole.ADMIN);
		long clientId = whatsAppClient("212622220001");
		long brandId = brand("Pay Brand", "paybrand.test");
		long paypal2 = method("PAYPAL", "Pay PayPal 2");
		Plan plan = plans.findByDevicesAndMonths((short) 2, (short) 3).orElseThrow();
		long perkId = perks.findByNameIgnoreCase("IBO Player").orElseThrow().getId();
		BigDecimal perkCost = perks.findById(perkId).orElseThrow().getCost();
		long creditsBefore = usedCredits(admin);

		// A private price, with two perks.
		String body = pay(commercial, clientId, "NEW_PLAN", 2, 3, "50", brandId, paypal2, perkId, 2)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.payment.amount").value(50.0))
			.andExpect(jsonPath("$.payment.planPrice").value(plan.getPrice().doubleValue()))
			.andExpect(jsonPath("$.payment.paymentMethodName").value("Pay PayPal 2"))
			.andExpect(jsonPath("$.payment.perks[0].quantity").value(2))
			.andReturn().getResponse().getContentAsString();
		LocalDate today = LocalDate.now(ZoneId.of("America/Toronto"));
		assertThat(JsonPath.<String>read(body, "$.payment.startsOn")).isEqualTo(today.toString());
		assertThat(JsonPath.<String>read(body, "$.payment.endsOn")).isEqualTo(today.plusMonths(3).toString());
		BigDecimal expense = plan.getCost().add(perkCost.multiply(BigDecimal.valueOf(2)));
		assertThat(new BigDecimal(JsonPath.read(body, "$.payment.expense").toString())).isEqualByComparingTo(expense);

		// The client: on the brand, Active, with the plan and the totals.
		Client client = clients.findById(clientId).orElseThrow();
		assertThat(client.getStatus()).isEqualTo(Client.Status.ACTIVE);
		assertThat(client.getBrandId()).isEqualTo(brandId);

		// A renewal starts where the running term ends.
		String renewal = pay(commercial, clientId, "RENEWAL", 2, 3, plan.getPrice().toPlainString(), brandId, paypal2, null, 0)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<String>read(renewal, "$.payment.startsOn")).isEqualTo(today.plusMonths(3).toString());
		assertThat(JsonPath.<String>read(renewal, "$.payment.endsOn")).isEqualTo(today.plusMonths(6).toString());

		mvc.perform(get("/api/clients/" + clientId).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orders").value(2))
			.andExpect(jsonPath("$.revenue").value(plan.getPrice().add(new BigDecimal("50")).doubleValue()))
			.andExpect(jsonPath("$.devices").value(2))
			.andExpect(jsonPath("$.months").value(3))
			.andExpect(jsonPath("$.subscriptionEnd").value(today.plusMonths(6).toString()))
			.andExpect(jsonPath("$.paymentMethodName").value("Pay PayPal 2"));

		// Panel credit spent, method balance grown.
		assertThat(usedCredits(admin)).isEqualTo(creditsBefore + 2L * plan.getCredits());
		String methodsList = mvc.perform(get("/api/payment-methods").header("Authorization", "Bearer " + admin))
			.andReturn().getResponse().getContentAsString();
		Double balance = JsonPath.<java.util.List<Double>>read(methodsList, "$[?(@.id == " + paypal2 + ")].balance").getFirst();
		assertThat(BigDecimal.valueOf(balance)).isEqualByComparingTo(plan.getPrice().add(new BigDecimal("50")));

		// Delete: Admins only; it leaves the totals and gives its credits back.
		long renewalId = ((Number) JsonPath.read(renewal, "$.payment.id")).longValue();
		mvc.perform(delete("/api/payments/" + renewalId).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/payments/" + renewalId).header("Authorization", "Bearer " + admin))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/clients/" + clientId + "/payments").header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
		assertThat(usedCredits(admin)).isEqualTo(creditsBefore + plan.getCredits());
	}

	@Test
	void onlyWhatConfigurationOffersCanBeSold() throws Exception {
		String commercial = token("pay-rules@onebase.test", UserRole.COMMERCIAL);
		long clientId = whatsAppClient("212622220002");
		long brandId = brand("Rules Brand", "rulesbrand.test");
		long method = method("INTERAC", "Rules Interac");
		pay(commercial, clientId, "NEW_PLAN", 7, 3, "10", brandId, method, null, 0).andExpect(status().isBadRequest());
		pay(commercial, clientId, "NEW_PLAN", 1, 1, "0", brandId, method, null, 0).andExpect(status().isBadRequest());
		pay(commercial, clientId, "NEW_PLAN", 1, 1, "10.555", brandId, method, null, 0).andExpect(status().isBadRequest());
		jdbc.update("update brands set active = false where id = ?", brandId);
		pay(commercial, clientId, "NEW_PLAN", 1, 1, "10", brandId, method, null, 0)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Choose a brand that is switched on."));
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions pay(String token, long clientId, String kind, int devices, int months, String amount, long brandId,
			long methodId, Long perkId, int perkCount) throws Exception {
		String perksJson = perkId == null ? "[]" : "[{\"perkId\":" + perkId + ",\"quantity\":" + perkCount + "}]";
		return mvc.perform(post("/api/clients/" + clientId + "/payments").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"" + kind + "\",\"devices\":" + devices + ",\"months\":" + months + ",\"amount\":" + amount
				+ ",\"brandId\":" + brandId + ",\"paymentMethodId\":" + methodId + ",\"perks\":" + perksJson + "}"));
	}

	private long usedCredits(String adminToken) throws Exception {
		String body = mvc.perform(get("/api/credit").header("Authorization", "Bearer " + adminToken))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.usedCredits")).longValue();
	}

	private long brand(String name, String domain) {
		return jdbc.queryForObject("insert into brands (name, domain, website_url) values (?, ?, ?) returning id", Long.class,
			name, domain, "https://" + domain);
	}

	private long method(String provider, String name) {
		return jdbc.queryForObject("insert into payment_methods (provider, name, holder) values (?, ?, 'Test') returning id",
			Long.class, provider, name);
	}

	private long whatsAppClient(String waId) throws Exception {
		String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"111\"},"
			+ "\"contacts\":[{\"wa_id\":\"" + waId + "\",\"profile\":{\"name\":\"Payer\"}}],"
			+ "\"messages\":[{\"from\":\"" + waId + "\",\"id\":\"wamid.P" + waId + "\",\"timestamp\":\"" + Instant.now().getEpochSecond()
			+ "\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}]}}]}]}";
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
			.andExpect(status().isOk());
		return clients.findByPhone("+" + waId).orElseThrow().getId();
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
