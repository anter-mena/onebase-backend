package com.onebase.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
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
	"onebase.report.key=test-report-key",
	"onebase.alerts.emails=team@onebase.test",
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
	void aPaymentFromTheWindowIsSavedWithItsProofOrNotAtAll() throws Exception {
		String commercial = token("pay-proof@onebase.test", UserRole.COMMERCIAL);
		long clientId = whatsAppClient("212622220003");
		long brandId = brand("Proof Brand", "proofbrand.test");
		long method = method("BINANCE", "Proof Binance");
		String payment = "{\"kind\":\"NEW_PLAN\",\"devices\":1,\"months\":1,\"amount\":10,\"brandId\":" + brandId
			+ ",\"paymentMethodId\":" + method + ",\"perks\":[]}";
		MockMultipartFile part = new MockMultipartFile("payment", "", MediaType.APPLICATION_JSON_VALUE, payment.getBytes(StandardCharsets.UTF_8));
		MockMultipartFile pdf = new MockMultipartFile("proofs", "invoice.pdf", "application/pdf", "%PDF-1.7 proof".getBytes(StandardCharsets.UTF_8));
		MockMultipartFile page = new MockMultipartFile("proofs", "invoice.html", "text/html", "<html>".getBytes(StandardCharsets.UTF_8));

		// The old JSON way, without files, is gone: a payment always comes with its proof.
		mvc.perform(post("/api/clients/" + clientId + "/payments").header("Authorization", "Bearer " + commercial)
				.contentType(MediaType.APPLICATION_JSON).content(payment))
			.andExpect(status().isUnsupportedMediaType());
		// No proof, or one that is not an invoice format: nothing is saved.
		mvc.perform(multipart("/api/clients/" + clientId + "/payments").file(part).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Add the proof of payment")));
		mvc.perform(multipart("/api/clients/" + clientId + "/payments").file(part).file(pdf).file(page).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("select count(*) from payments where client_id = ?", Long.class, clientId)).isZero();

		// With a PDF: the payment, and its proof beside it, typed from the bytes.
		String body = mvc.perform(multipart("/api/clients/" + clientId + "/payments").file(part).file(pdf).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		long paymentId = ((Number) JsonPath.read(body, "$.payment.id")).longValue();
		assertThat(jdbc.queryForObject("select mime from payment_proofs where payment_id = ?", String.class, paymentId)).isEqualTo("application/pdf");

		// Listed with the payment (names, not bytes), and served as exactly what it is.
		long proofId = jdbc.queryForObject("select id from payment_proofs where payment_id = ?", Long.class, paymentId);
		mvc.perform(get("/api/clients/" + clientId + "/payments").header("Authorization", "Bearer " + commercial))
			.andExpect(jsonPath("$[0].proofs[0].filename").value("invoice.pdf"))
			.andExpect(jsonPath("$[0].proofs[0].mime").value("application/pdf"));
		String proofUrl = "/api/clients/" + clientId + "/payments/" + paymentId + "/proofs/" + proofId;
		mvc.perform(get(proofUrl).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/pdf"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("%PDF-1.7 proof"));
		// Only through its own client and payment.
		long otherClient = whatsAppClient("212622220004");
		mvc.perform(get("/api/clients/" + otherClient + "/payments/" + paymentId + "/proofs/" + proofId).header("Authorization", "Bearer " + commercial))
			.andExpect(status().isNotFound());
	}

	@Test
	void aBrandCountsClientsNotPlansAndItsLineAddsUp() throws Exception {
		String admin = token("pay-brand-count@onebase.test", UserRole.ADMIN);
		long brandId = brand("Count Brand", "countbrand.test");
		long method = method("PAYPAL", "Count PayPal");
		long many = whatsAppClient("212622220010");
		long once = whatsAppClient("212622220011");
		// One client with three plans, one with a single plan: two clients.
		for (int i = 0; i < 3; i++) pay(admin, many, i == 0 ? "NEW_PLAN" : "RENEWAL", 1, 1, "10", brandId, method, null, 0).andExpect(status().isCreated());
		pay(admin, once, "NEW_PLAN", 1, 1, "10", brandId, method, null, 0).andExpect(status().isCreated());

		String list = mvc.perform(get("/api/brands").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<Integer>>read(list, "$[?(@.id == " + brandId + ")].clients")).containsExactly(2);
		java.util.List<Integer> quarters = JsonPath.<java.util.List<java.util.List<Integer>>>read(list,
			"$[?(@.id == " + brandId + ")].clientsByQuarter").getFirst();
		assertThat(quarters).hasSize(4);
		// Both were added this quarter: the line adds up to the count beside it.
		int thisQuarter = (LocalDate.now(ZoneId.of("America/Toronto")).getMonthValue() - 1) / 3;
		assertThat(quarters.get(thisQuarter)).isEqualTo(2);
		assertThat(quarters.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
	}

	@Test
	void theWeeklyReportNeedsItsKeyAndCountsTheWeek() throws Exception {
		String admin = token("pay-report@onebase.test", UserRole.ADMIN);
		long clientId = whatsAppClient("212622220020");
		long brandId = brand("Report Brand", "reportbrand.test");
		long method = method("INTERAC", "Report Interac");
		String today = LocalDate.now(ZoneId.of("America/Toronto")).toString();

		// No key, a wrong key, a signed-in person: refused. It opens with its own key only.
		mvc.perform(get("/api/reports/weekly")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/reports/weekly").header("X-Report-Key", "wrong")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/reports/weekly").header("Authorization", "Bearer " + admin)).andExpect(status().isUnauthorized());

		String before = mvc.perform(get("/api/reports/weekly").param("week", today).header("X-Report-Key", "test-report-key"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		pay(admin, clientId, "NEW_PLAN", 1, 1, "25", brandId, method, null, 0).andExpect(status().isCreated());
		String after = mvc.perform(get("/api/reports/weekly").param("week", today).header("X-Report-Key", "test-report-key"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.subject").value(org.hamcrest.Matchers.startsWith("One Base weekly report")))
			.andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("Report Brand")))
			.andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(after, "$.report.money.payments")).intValue())
			.isEqualTo(((Number) JsonPath.read(before, "$.report.money.payments")).intValue() + 1);
		// A week runs Sunday to Saturday.
		assertThat(LocalDate.parse(JsonPath.<String>read(after, "$.start")).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
		assertThat(LocalDate.parse(JsonPath.<String>read(after, "$.end")).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
		// The email alone, to look at it.
		mvc.perform(get("/api/reports/weekly").param("format", "html").header("X-Report-Key", "test-report-key"))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
	}

	@Test
	void aPaymentThatTakesTheCreditDownToFiftyWarnsTheTeamOnce() throws Exception {
		String admin = token("pay-lowcredit@onebase.test", UserRole.ADMIN);
		long clientId = whatsAppClient("212622220030");
		long brandId = brand("Low Credit Brand", "lowcredit.test");
		long method = method("PAYPAL", "Low Credit PayPal");
		int planCredits = plans.findByDevicesAndMonths((short) 2, (short) 3).orElseThrow().getCredits();
		// Top up so that this plan's payment lands exactly on 50.
		String credit = mvc.perform(get("/api/credit").header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString();
		long left = ((Number) JsonPath.read(credit, "$.remainingCredits")).longValue();
		long topUp = 50 + planCredits - left;
		org.junit.jupiter.api.Assumptions.assumeTrue(topUp > 0, "the shared test database already holds more credit than this test can bring to 50");
		mvc.perform(post("/api/credit/topups").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"credits\":" + topUp + ",\"amount\":1,\"note\":\"Low credit test\"}"))
			.andExpect(status().isCreated());

		pay(admin, clientId, "NEW_PLAN", 2, 3, "10", brandId, method, null, 0).andExpect(status().isCreated());
		org.mockito.Mockito.verify(mail, org.mockito.Mockito.timeout(5000)).sendAlert(org.mockito.ArgumentMatchers.eq(java.util.List.of("team@onebase.test")),
			org.mockito.ArgumentMatchers.eq("One Base · Panel credit low: 50 credits left"), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.contains("Went below with"));
		// Already low: the next payment says nothing more.
		pay(admin, clientId, "RENEWAL", 2, 3, "10", brandId, method, null, 0).andExpect(status().isCreated());
		org.mockito.Mockito.verify(mail, org.mockito.Mockito.after(2000).times(1)).sendAlert(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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

	@Test
	void renewalsShowWhoEndsWithinTenDaysAndWhoEnded() throws Exception {
		String commercial = token("pay-renew@onebase.test", UserRole.COMMERCIAL);
		long brandId = brand("Renew Brand", "renewbrand.test");
		long method = method("PAYPAL", "Renew PayPal");
		long soon = whatsAppClient("212622220010");
		long later = whatsAppClient("212622220011");
		long ended = whatsAppClient("212622220012");
		for (long id : new long[] { soon, later, ended }) {
			pay(commercial, id, "NEW_PLAN", 1, 1, "10", brandId, method, null, 0).andExpect(status().isCreated());
		}
		LocalDate today = LocalDate.now(ZoneId.of("America/Toronto"));
		jdbc.update("update payments set ends_on = ? where client_id = ?", today.plusDays(10), soon);
		jdbc.update("update payments set ends_on = ? where client_id = ?", today.plusDays(11), later);
		jdbc.update("update payments set starts_on = ?, ends_on = ? where client_id = ?", today.minusDays(40), today.minusDays(3), ended);
		jdbc.update("update clients set status = 'INACTIVE' where id = ?", ended);

		String body = mvc.perform(get("/api/renewals").header("Authorization", "Bearer " + commercial))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<String>>read(body, "$[?(@.client.id == " + soon + ")].group")).containsExactly("ENDING_SOON");
		assertThat(JsonPath.<java.util.List<Integer>>read(body, "$[?(@.client.id == " + soon + ")].daysLeft")).containsExactly(10);
		assertThat(JsonPath.<java.util.List<String>>read(body, "$[?(@.client.id == " + later + ")].group")).isEmpty();
		assertThat(JsonPath.<java.util.List<String>>read(body, "$[?(@.client.id == " + ended + ")].group")).containsExactly("INACTIVE");
		assertThat(JsonPath.<java.util.List<Integer>>read(body, "$[?(@.client.id == " + ended + ")].daysLeft")).containsExactly(-3);
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private ResultActions pay(String token, long clientId, String kind, int devices, int months, String amount, long brandId,
			long methodId, Long perkId, int perkCount) throws Exception {
		String perksJson = perkId == null ? "[]" : "[{\"perkId\":" + perkId + ",\"quantity\":" + perkCount + "}]";
		return mvc.perform(PaymentRequests.addPayment(clientId, "{\"kind\":\"" + kind + "\",\"devices\":" + devices + ",\"months\":" + months + ",\"amount\":" + amount
				+ ",\"brandId\":" + brandId + ",\"paymentMethodId\":" + methodId + ",\"perks\":" + perksJson + "}")
			.header("Authorization", "Bearer " + token));
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
