package com.onebase.seo;

import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.common.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SEO Overview: what organic search brings a brand, read live from its Google
 * Analytics 4 property.
 *
 * <p><b>Nothing is stored.</b> Each answer is kept ten minutes in memory (per brand
 * and range), so opening the page again or switching back and forth costs nothing,
 * and the GA4 quota is never near its limit.
 *
 * <p>"Organic" is GA4's own channel grouping: {@code sessionDefaultChannelGroup =
 * "Organic Search"}. The channel mix is the one report that is not filtered, since
 * it puts organic next to everything else. Complete days only (up to yesterday),
 * compared with the period just before.
 */
@Service
public class SeoService {

	static final long CACHE_MILLIS = 10 * 60 * 1000;
	private static final DateTimeFormatter GA_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	public record SeoBrand(long id, String name, String logoUrl, String propertyId) {
	}

	public record TrafficPoint(String start, long sessions, long users) {
	}

	public record Totals(long sessions, long previousSessions, long users, long previousUsers,
			double engagementRate, double previousEngagementRate, long keyEvents, long previousKeyEvents,
			double averageEngagementSeconds, double previousAverageEngagementSeconds) {
	}

	public record Channel(String channel, long sessions) {
	}

	public record LandingPage(String path, long sessions, double engagementRate, long keyEvents) {
	}

	public record Engine(String source, long sessions) {
	}

	public record Country(String country, String countryId, long sessions) {
	}

	public record Device(String device, long sessions) {
	}

	/**
	 * {@code range}: today, yesterday, 7d, month, year or custom; {@code start}/{@code end} the dates
	 * it covers; {@code step} the chart's step (HOUR, DAY, WEEK, MONTH).
	 */
	public record Overview(SeoBrand brand, String range, String start, String end, String step, Instant fetchedAt,
			List<TrafficPoint> traffic, Totals totals,
			List<Channel> channels, List<LandingPage> landingPages, long landingTailSessions, List<Engine> engines,
			List<Country> countries, List<Device> devices) {
	}

	private record Cached(long until, Overview overview) {
	}

	private final Ga4Client ga4;
	private final BrandRepository brands;
	private final ZoneId zone;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public SeoService(Ga4Client ga4, BrandRepository brands,
			@org.springframework.beans.factory.annotation.Value("${onebase.seo.timezone:America/Toronto}") String timezone) {
		this.ga4 = ga4;
		this.brands = brands;
		this.zone = ZoneId.of(timezone);
	}

	/** Active brands with a GA4 property, oldest first: the brand switch on the page. */
	public List<SeoBrand> brands() {
		return brands.findAllByOrderByCreatedAtAscIdAsc().stream()
			.filter(Brand::isActive)
			.filter(b -> b.getGa4PropertyId() != null)
			.map(SeoService::view)
			.toList();
	}

	public Overview overview(long brandId, String range, String from, String to) {
		SeoPeriod period = SeoPeriod.of(range, from, to, LocalDate.now(zone));
		Brand brand = brands.findById(brandId).orElseThrow(() -> ApiException.notFound("This brand does not exist."));
		if (brand.getGa4PropertyId() == null) {
			throw ApiException.badRequest("This brand has no GA4 property yet. Add it in Configuration → Brands.");
		}
		String key = brandId + "|" + brand.getGa4PropertyId() + "|" + period.cacheKey();
		Cached hit = cache.get(key);
		if (hit != null && hit.until() > System.currentTimeMillis()) return hit.overview();

		Overview fresh = fetch(brand, period);
		cache.put(key, new Cached(System.currentTimeMillis() + CACHE_MILLIS, fresh));
		return fresh;
	}

	private Overview fetch(Brand brand, SeoPeriod period) {
		String[] current = {period.start().toString(), period.end().toString()};
		String[] previous = {period.previousStart().toString(), period.previousEnd().toString()};
		String stepDimension = switch (period.step()) {
			case HOUR -> "hour";
			case DAY -> "date";
			case WEEK -> "nthWeek";
			case MONTH -> "yearMonth";
		};
		String property = brand.getGa4PropertyId();

		List<JsonNode> first = ga4.batch(property, List.of(
			report(List.of(stepDimension), List.of("sessions", "totalUsers"), only(current), true, null, null),
			report(List.of(), List.of("sessions", "totalUsers", "engagementRate", "keyEvents", "userEngagementDuration", "activeUsers"),
				List.of(current, previous), true, null, null),
			report(List.of("sessionDefaultChannelGroup"), List.of("sessions"), only(current), false, "sessions", 8),
			report(List.of("landingPage"), List.of("sessions", "engagementRate", "keyEvents"), only(current), true, "sessions", 5),
			report(List.of("sessionSource"), List.of("sessions"), only(current), true, "sessions", 5)));
		List<JsonNode> second = ga4.batch(property, List.of(
			report(List.of("countryId", "country"), List.of("sessions"), only(current), true, "sessions", 8),
			report(List.of("deviceCategory"), List.of("sessions"), only(current), true, "sessions", null)));

		Totals totals = totals(first.get(1));
		List<LandingPage> pages = new ArrayList<>();
		for (JsonNode row : rows(first.get(3))) {
			pages.add(new LandingPage(dim(row, 0), longMetric(row, 0), percent(row, 1), longMetric(row, 2)));
		}
		long pageSessions = pages.stream().mapToLong(LandingPage::sessions).sum();

		List<Engine> engines = new ArrayList<>();
		for (JsonNode row : rows(first.get(4))) engines.add(new Engine(dim(row, 0), longMetric(row, 0)));
		long engineSessions = engines.stream().mapToLong(Engine::sessions).sum();
		// The engines add up to the organic total: whatever the top five leave is "other".
		if (totals.sessions() > engineSessions && !engines.isEmpty()) engines.add(new Engine("other", totals.sessions() - engineSessions));

		List<Channel> channels = new ArrayList<>();
		for (JsonNode row : rows(first.get(2))) channels.add(new Channel(dim(row, 0), longMetric(row, 0)));
		// Organic first, the channel this page is about; the rest largest-first.
		channels.sort((a, b) -> a.channel().equals("Organic Search") ? -1 : b.channel().equals("Organic Search") ? 1
			: Long.compare(b.sessions(), a.sessions()));

		List<Country> countries = new ArrayList<>();
		for (JsonNode row : rows(second.get(0))) {
			if (!"(not set)".equals(dim(row, 0))) countries.add(new Country(dim(row, 1), dim(row, 0), longMetric(row, 0)));
		}
		List<Device> devices = new ArrayList<>();
		for (JsonNode row : rows(second.get(1))) devices.add(new Device(dim(row, 0), longMetric(row, 0)));

		return new Overview(view(brand), period.range(), period.start().toString(), period.end().toString(), period.step().name(),
			Instant.now(), traffic(first.get(0), period), totals, channels,
			pages, Math.max(0, totals.sessions() - pageSessions), engines, countries, devices);
	}

	/** Every hour, day, week or month of the period, with zeros where GA4 had nothing. */
	private static List<TrafficPoint> traffic(JsonNode report, SeoPeriod period) {
		Map<String, long[]> byKey = new LinkedHashMap<>();
		for (JsonNode row : rows(report)) byKey.put(dim(row, 0), new long[] {longMetric(row, 0), longMetric(row, 1)});
		List<TrafficPoint> points = new ArrayList<>();
		switch (period.step()) {
			case HOUR -> {
				for (int h = 0; h < 24; h++) {
					long[] v = byKey.getOrDefault(String.format("%02d", h), new long[2]);
					points.add(new TrafficPoint(period.start() + "T" + String.format("%02d", h) + ":00", v[0], v[1]));
				}
			}
			case DAY -> {
				for (LocalDate day = period.start(); !day.isAfter(period.end()); day = day.plusDays(1)) {
					long[] v = byKey.getOrDefault(day.format(GA_DATE), new long[2]);
					points.add(new TrafficPoint(day.toString(), v[0], v[1]));
				}
			}
			case WEEK -> {
				long weeks = (period.days() + 6) / 7;
				for (int n = 0; n < weeks; n++) {
					long[] v = byKey.getOrDefault(String.format("%04d", n), new long[2]);
					points.add(new TrafficPoint(period.start().plusDays(7L * n).toString(), v[0], v[1]));
				}
			}
			case MONTH -> {
				for (LocalDate month = period.start().withDayOfMonth(1); !month.isAfter(period.end()); month = month.plusMonths(1)) {
					long[] v = byKey.getOrDefault(month.format(DateTimeFormatter.ofPattern("yyyyMM")), new long[2]);
					LocalDate shown = month.isBefore(period.start()) ? period.start() : month;
					points.add(new TrafficPoint(shown.toString(), v[0], v[1]));
				}
			}
		}
		return points;
	}

	/** Two date ranges: GA4 adds a "dateRange" column (date_range_0 = current, date_range_1 = previous). */
	private static Totals totals(JsonNode report) {
		double[] now = new double[6];
		double[] before = new double[6];
		for (JsonNode row : rows(report)) {
			double[] target = "date_range_1".equals(dim(row, 0)) ? before : now;
			for (int i = 0; i < 6; i++) target[i] = doubleMetric(row, i);
		}
		return new Totals((long) now[0], (long) before[0], (long) now[1], (long) before[1],
			oneDecimal(now[2] * 100), oneDecimal(before[2] * 100), (long) now[3], (long) before[3],
			now[5] == 0 ? 0 : oneDecimal(now[4] / now[5]), before[5] == 0 ? 0 : oneDecimal(before[4] / before[5]));
	}

	private ObjectNode report(List<String> dimensions, List<String> metrics, List<String[]> ranges, boolean organic,
			String orderBy, Integer limit) {
		ObjectNode r = ga4.json().createObjectNode();
		ArrayNode dateRanges = r.putArray("dateRanges");
		for (String[] range : ranges) {
			dateRanges.addObject().put("startDate", range[0]).put("endDate", range[1]);
		}
		ArrayNode dims = r.putArray("dimensions");
		dimensions.forEach(d -> dims.addObject().put("name", d));
		ArrayNode mets = r.putArray("metrics");
		metrics.forEach(m -> mets.addObject().put("name", m));
		if (organic) {
			ObjectNode filter = r.putObject("dimensionFilter").putObject("filter");
			filter.put("fieldName", "sessionDefaultChannelGroup");
			filter.putObject("stringFilter").put("value", "Organic Search");
		}
		if (orderBy != null) {
			r.putArray("orderBys").addObject().put("desc", true).putObject("metric").put("metricName", orderBy);
		}
		if (limit != null) r.put("limit", limit);
		return r;
	}

	/** One date range. (List.of(array) would unpack the array.) */
	private static List<String[]> only(String[] range) {
		List<String[]> list = new ArrayList<>();
		list.add(range);
		return list;
	}

	private static Iterable<JsonNode> rows(JsonNode report) {
		JsonNode rows = report == null ? null : report.path("rows");
		return rows != null && rows.isArray() ? rows.valueStream().toList() : List.of();
	}

	private static String dim(JsonNode row, int index) {
		return row.path("dimensionValues").path(index).path("value").asString("");
	}

	private static double doubleMetric(JsonNode row, int index) {
		try {
			return Double.parseDouble(row.path("metricValues").path(index).path("value").asString("0"));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long longMetric(JsonNode row, int index) {
		return Math.round(doubleMetric(row, index));
	}

	/** GA4 gives rates as 0.584; the page shows 58.4 (%). One decimal. */
	private static double percent(JsonNode row, int index) {
		return oneDecimal(doubleMetric(row, index) * 100);
	}

	private static double oneDecimal(double value) {
		return Math.round(value * 10) / 10.0;
	}

	private static SeoBrand view(Brand b) {
		String logo = b.getLogo() == null || b.getLogoUpdatedAt() == null ? null
			: "/api/brands/" + b.getId() + "/logo?v=" + b.getLogoUpdatedAt().toEpochMilli();
		return new SeoBrand(b.getId(), b.getName(), logo, b.getGa4PropertyId());
	}
}
