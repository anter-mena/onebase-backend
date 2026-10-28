package com.onebase.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.onebase.TestcontainersConfiguration;
import com.onebase.mail.MailService;
import com.onebase.payment.PaymentRequests;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import com.onebase.user.UserRole;
import com.onebase.whatsapp.WhatsAppCloudApi;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The Dashboard and the Ledger: Admins only, worked out from the payments, the period filter, the target. */
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
class DashboardModuleTests {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean MailService mail;
	@MockitoBean WhatsAppCloudApi api;

	@Test
	void dashboardAndLedgerAddUpThePaymentsForAdminsOnly() throws Exception {
		String admin = token("dash-admin@onebase.test", UserRole.ADMIN);
		String commercial = token("dash-com@onebase.test", UserRole.COMMERCIAL);
		mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		mvc.perform(get("/api/ledger").header("Authorization", "Bearer " + commercial)).andExpect(status().isForbidden());
		mvc.perform(put("/api/dashboard/target").header("Authorization", "Bearer " + commercial).contentType(MediaType.APPLICATION_JSON)
			.content("{\"target\":100}")).andExpect(status().isForbidden());

		String before = mvc.perform(get("/api/dashboard").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		BigDecimal revenueBefore = new BigDecimal(JsonPath.read(before, "$.totals.revenue").toString());

		long brand = jdbc.queryForObject("insert into brands (name, domain, website_url) values ('Dash Brand', 'dash.test', 'https://dash.test') returning id", Long.class);
		long cardA = jdbc.queryForObject("insert into payment_methods (provider, name, holder) values ('PAYPAL', 'Dash PayPal A', 'T') returning id", Long.class);
		long cardB = jdbc.queryForObject("insert into payment_methods (provider, name, holder) values ('INTERAC', 'Dash Interac B', 'T') returning id", Long.class);
		long client = whatsAppClient("212633330001");
		pay(commercial, client, "NEW_PLAN", "40", brand, cardA);
		pay(commercial, client, "RENEWAL", "25.50", brand, cardB);

		String today = mvc.perform(get("/api/dashboard").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.step").value("HOUR"))
			.andExpect(jsonPath("$.points.length()").value(24))
			.andReturn().getResponse().getContentAsString();
		assertThat(new BigDecimal(JsonPath.read(today, "$.totals.revenue").toString())).isEqualByComparingTo(revenueBefore.add(new BigDecimal("65.50")));
		List<Double> brandRevenue = JsonPath.read(today, "$.brands[?(@.id == " + brand + ")].revenue");
		assertThat(brandRevenue).containsExactly(65.5);
		List<Double> cardAReceived = JsonPath.read(today, "$.methods[?(@.id == " + cardA + ")].received");
		assertThat(cardAReceived).containsExactly(40.0);
		double pointsSum = JsonPath.<List<Double>>read(today, "$.points[*].newRevenue").stream().mapToDouble(Double::doubleValue).sum()
			+ JsonPath.<List<Double>>read(today, "$.points[*].renewals").stream().mapToDouble(Double::doubleValue).sum();
		assertThat(new BigDecimal(String.valueOf(pointsSum))).isEqualByComparingTo(new BigDecimal(JsonPath.read(today, "$.totals.revenue").toString()));

		// Yesterday saw none of it.
		String yesterday = mvc.perform(get("/api/dashboard").param("range", "yesterday").header("Authorization", "Bearer " + admin))
			.andReturn().getResponse().getContentAsString();
		List<Double> yesterdayBrand = JsonPath.read(yesterday, "$.brands[?(@.id == " + brand + ")].revenue");
		assertThat(yesterdayBrand).containsExactly(0.0);

		// The target.
		mvc.perform(put("/api/dashboard/target").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"target\":0}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/dashboard/target").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"target\":20000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.target").value(20000.0));
		mvc.perform(get("/api/dashboard").param("range", "month").header("Authorization", "Bearer " + admin))
			.andExpect(jsonPath("$.target.target").value(20000.0));

		// The Ledger, all accounts and one.
		String ledger = mvc.perform(get("/api/ledger").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Object>>read(ledger, "$.rows[?(@.clientId == " + client + ")]")).hasSize(2);
		String onlyB = mvc.perform(get("/api/ledger").param("range", "today").param("methodId", String.valueOf(cardB))
				.header("Authorization", "Bearer " + admin))
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Object>>read(onlyB, "$.rows")).hasSize(1);
		assertThat(JsonPath.<String>read(onlyB, "$.rows[0].payment.paymentMethodName")).isEqualTo("Dash Interac B");

		mvc.perform(get("/api/dashboard").param("range", "decade").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/ledger").param("range", "custom").param("from", "2026-01-10").param("to", "2026-01-01")
			.header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
	}

	@Test
	void moneyOutIsTheTopUpsPaidAndThePerksNotThePlanCost() throws Exception {
		String admin = token("dash-cash-admin@onebase.test", UserRole.ADMIN);
		String before = mvc.perform(get("/api/dashboard").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		long brand = jdbc.queryForObject("insert into brands (name, domain, website_url) values ('Cash Brand', 'cash.test', 'https://cash.test') returning id", Long.class);
		long card = jdbc.queryForObject("insert into payment_methods (provider, name, holder) values ('PAYPAL', 'Cash PayPal', 'T') returning id", Long.class);
		long perk = jdbc.queryForObject("insert into perks (name, cost) values ('Cash Perk', 3.25) returning id", Long.class);
		long client = whatsAppClient("212633330002");
		// A sale: its plan's cost is not money out (the credit was paid by a top-up); its perk is.
		mvc.perform(PaymentRequests.addPayment(client, "{\"kind\":\"NEW_PLAN\",\"devices\":1,\"months\":1,\"amount\":50,\"brandId\":" + brand
					+ ",\"paymentMethodId\":" + card + ",\"perks\":[{\"perkId\":" + perk + ",\"quantity\":2}]}")
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isCreated());
		// A top-up: money out today.
		mvc.perform(post("/api/credit/topups").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"credits\":100,\"amount\":120,\"note\":\"Cash view top-up\"}"))
			.andExpect(status().isCreated());

		String today = mvc.perform(get("/api/dashboard").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.uncosted").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(delta(today, before, "$.totals.topups")).isEqualByComparingTo("120");
		assertThat(delta(today, before, "$.totals.perks")).isEqualByComparingTo("6.50");
		assertThat(delta(today, before, "$.totals.expenses")).isEqualByComparingTo("126.50");
		assertThat(number(today, "$.totals.expenses"))
			.isEqualByComparingTo(number(today, "$.totals.topups").add(number(today, "$.totals.perks")));
		assertThat(number(today, "$.totals.net"))
			.isEqualByComparingTo(number(today, "$.totals.revenue").subtract(number(today, "$.totals.expenses")));
		double chartExpenses = JsonPath.<List<Number>>read(today, "$.points[*].expenses").stream().mapToDouble(Number::doubleValue).sum();
		assertThat(new BigDecimal(String.valueOf(chartExpenses))).isEqualByComparingTo(number(today, "$.totals.expenses"));

		// The Ledger lists the top-up as money out — but not when filtered to an account (it is paid from none).
		String ledger = mvc.perform(get("/api/ledger").param("range", "today").header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(ledger, "$.topups[?(@.note == 'Cash view top-up')].createdByName"))
			.containsExactly("Test User");
		assertThat(JsonPath.<List<Integer>>read(ledger, "$.topups[?(@.note == 'Cash view top-up')].credits")).containsExactly(100);
		double rowsIn = JsonPath.<List<Number>>read(ledger, "$.rows[*].payment.amount").stream().mapToDouble(Number::doubleValue).sum();
		assertThat(number(ledger, "$.moneyIn")).isEqualByComparingTo(new BigDecimal(String.valueOf(rowsIn)));
		assertThat(number(ledger, "$.moneyOut")).isEqualByComparingTo(number(today, "$.totals.expenses"));
		assertThat(number(ledger, "$.net")).isEqualByComparingTo(number(ledger, "$.moneyIn").subtract(number(ledger, "$.moneyOut")));

		String onlyCard = mvc.perform(get("/api/ledger").param("range", "today").param("methodId", String.valueOf(card))
				.header("Authorization", "Bearer " + admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topups.length()").value(0))
			.andReturn().getResponse().getContentAsString();
		assertThat(number(onlyCard, "$.moneyIn")).isEqualByComparingTo("50");
		assertThat(number(onlyCard, "$.moneyOut")).isEqualByComparingTo("6.50");
		assertThat(number(onlyCard, "$.net")).isEqualByComparingTo("43.50");
	}

	private static BigDecimal number(String json, String path) {
		return new BigDecimal(JsonPath.read(json, path).toString());
	}

	private static BigDecimal delta(String after, String before, String path) {
		return number(after, path).subtract(number(before, path));
	}

	private void pay(String token, long clientId, String kind, String amount, long brand, long method) throws Exception {
		mvc.perform(PaymentRequests.addPayment(clientId, "{\"kind\":\"" + kind + "\",\"devices\":1,\"months\":1,\"amount\":" + amount + ",\"brandId\":" + brand
					+ ",\"paymentMethodId\":" + method + ",\"perks\":[]}")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isCreated());
	}

	private long whatsAppClient(String waId) throws Exception {
		String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
			+ "\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"111\"},"
			+ "\"contacts\":[{\"wa_id\":\"" + waId + "\",\"profile\":{\"name\":\"Dash\"}}],"
			+ "\"messages\":[{\"from\":\"" + waId + "\",\"id\":\"wamid.D" + waId + "\",\"timestamp\":\"" + Instant.now().getEpochSecond()
			+ "\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}]}}]}]}";
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
			.andExpect(status().isOk());
		return jdbc.queryForObject("select id from clients where phone = ?", Long.class, "+" + waId);
	}

	private String token(String email, UserRole role) throws Exception {
		User user = users.save(new User("Test User", email, encoder.encode("user-password"), role));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"user-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}
}
