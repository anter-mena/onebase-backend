package com.onebase.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.common.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The SEO page's numbers from GA4 answers, without Google: totals, gaps, "other", order, the cache. */
class SeoServiceTests {

	private final ObjectMapper json = JsonMapper.builder().build();

	@Test
	void ga4AnswersBecomeThePageAndAreKeptTenMinutes() {
		Ga4Client ga4 = mock(Ga4Client.class);
		when(ga4.json()).thenReturn(json);
		BrandRepository brands = mock(BrandRepository.class);
		Brand brand = mock(Brand.class);
		when(brand.getId()).thenReturn(3L);
		when(brand.getName()).thenReturn("Easy IPTV");
		when(brand.getGa4PropertyId()).thenReturn("412305881");
		when(brand.isActive()).thenReturn(true);
		when(brands.findById(3L)).thenReturn(Optional.of(brand));

		String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
		List<JsonNode> first = List.of(
			report("[[\"" + yesterday + "\"],[\"x\"]]", "[[\"40\",\"30\"],[\"1\",\"1\"]]"),
			report("[[\"date_range_0\"],[\"date_range_1\"]]",
				"[[\"100\",\"80\",\"0.5\",\"6\",\"9000\",\"60\"],[\"50\",\"40\",\"0.4\",\"3\",\"3000\",\"30\"]]"),
			report("[[\"Direct\"],[\"Organic Search\"],[\"Referral\"]]", "[[\"300\"],[\"100\"],[\"20\"]]"),
			report("[[\"/\"],[\"/setup\"]]", "[[\"60\",\"0.55\",\"2\"],[\"25\",\"0.7\",\"1\"]]"),
			report("[[\"google\"],[\"bing\"]]", "[[\"90\"],[\"6\"]]"));
		List<JsonNode> second = List.of(
			report("[[\"CA\",\"Canada\"],[\"(not set)\",\"(not set)\"]]", "[[\"70\"],[\"5\"]]"),
			report("[[\"mobile\"],[\"desktop\"]]", "[[\"65\"],[\"35\"]]"));
		when(ga4.batch(eq("412305881"), anyList())).thenReturn(first, second);

		SeoService service = new SeoService(ga4, brands, "UTC");
		SeoService.Overview o = service.overview(3, "7d", null, null);

		assertThat(o.traffic()).hasSize(7);
		assertThat(o.traffic().get(6).sessions()).isEqualTo(40);
		assertThat(o.traffic().get(0).sessions()).isZero();
		assertThat(o.totals().sessions()).isEqualTo(100);
		assertThat(o.totals().previousSessions()).isEqualTo(50);
		assertThat(o.totals().engagementRate()).isEqualTo(50.0);
		assertThat(o.totals().averageEngagementSeconds()).isEqualTo(150.0);
		assertThat(o.channels()).extracting(SeoService.Channel::channel).containsExactly("Organic Search", "Direct", "Referral");
		assertThat(o.landingPages()).extracting(SeoService.LandingPage::engagementRate).containsExactly(55.0, 70.0);
		assertThat(o.landingTailSessions()).isEqualTo(15);
		assertThat(o.engines()).extracting(SeoService.Engine::source).containsExactly("google", "bing", "other");
		assertThat(o.engines().get(2).sessions()).isEqualTo(4);
		assertThat(o.countries()).extracting(SeoService.Country::countryId).containsExactly("CA");

		// Kept ten minutes: a second look asks Google nothing.
		service.overview(3, "7d", null, null);
		verify(ga4, times(2)).batch(eq("412305881"), anyList());
	}

	@Test
	void aBrandWithoutAPropertyIsRefusedPolitely() {
		BrandRepository brands = mock(BrandRepository.class);
		Brand brand = mock(Brand.class);
		when(brands.findById(4L)).thenReturn(Optional.of(brand));
		assertThatThrownBy(() -> new SeoService(mock(Ga4Client.class), brands, "UTC").overview(4, "month", null, null))
			.isInstanceOf(ApiException.class).hasMessageContaining("no GA4 property");
	}

	@Test
	void eachPeriodHasItsDatesItsComparisonAndItsStep() {
		LocalDate today = LocalDate.of(2026, 10, 5);
		SeoPeriod day = SeoPeriod.of("today", null, null, today);
		assertThat(day.step()).isEqualTo(SeoPeriod.Step.HOUR);
		assertThat(day.previousStart()).isEqualTo(LocalDate.of(2026, 10, 4));
		SeoPeriod yesterday = SeoPeriod.of("yesterday", null, null, today);
		assertThat(yesterday.start()).isEqualTo(LocalDate.of(2026, 10, 4));
		SeoPeriod week = SeoPeriod.of("7d", null, null, today);
		assertThat(week.start()).isEqualTo(LocalDate.of(2026, 9, 28));
		assertThat(week.end()).isEqualTo(LocalDate.of(2026, 10, 4));
		assertThat(week.previousStart()).isEqualTo(LocalDate.of(2026, 9, 21));
		SeoPeriod month = SeoPeriod.of("month", null, null, today);
		assertThat(month.start()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(month.previousEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
		assertThat(month.step()).isEqualTo(SeoPeriod.Step.DAY);
		SeoPeriod year = SeoPeriod.of("year", null, null, today);
		assertThat(year.start()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(year.step()).isEqualTo(SeoPeriod.Step.MONTH);
		assertThat(SeoPeriod.of("custom", "2026-06-01", "2026-08-31", today).step()).isEqualTo(SeoPeriod.Step.WEEK);
		assertThatThrownBy(() -> SeoPeriod.of("custom", "2026-09-10", "2026-09-01", today)).hasMessageContaining("before the start");
		assertThatThrownBy(() -> SeoPeriod.of("custom", "2026-10-01", "2026-10-09", today)).hasMessageContaining("future");
		assertThatThrownBy(() -> SeoPeriod.of("custom", "x", "y", today)).hasMessageContaining("start and an end");
		assertThatThrownBy(() -> SeoPeriod.of("forever", null, null, today)).hasMessageContaining("Unknown period");
	}

	private JsonNode report(String dims, String mets) {
		JsonNode d = json.readTree(dims);
		JsonNode m = json.readTree(mets);
		StringBuilder rows = new StringBuilder("{\"rows\":[");
		for (int i = 0; i < d.size(); i++) {
			if (i > 0) rows.append(',');
			rows.append("{\"dimensionValues\":[");
			for (int j = 0; j < d.get(i).size(); j++) rows.append(j > 0 ? "," : "").append("{\"value\":").append(d.get(i).get(j)).append('}');
			rows.append("],\"metricValues\":[");
			for (int j = 0; j < m.get(i).size(); j++) rows.append(j > 0 ? "," : "").append("{\"value\":").append(m.get(i).get(j)).append('}');
			rows.append("]}");
		}
		return json.readTree(rows.append("]}").toString());
	}
}
