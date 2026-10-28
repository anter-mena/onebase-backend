package com.onebase.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.onebase.report.WeeklyReport.Activity;
import com.onebase.report.WeeklyReport.Attention;
import com.onebase.report.WeeklyReport.Clients;
import com.onebase.report.WeeklyReport.Credit;
import com.onebase.report.WeeklyReport.Line;
import com.onebase.report.WeeklyReport.Money;
import com.onebase.report.WeeklyReport.Person;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The weekly report's week, its words and numbers, and an email no name can break. */
class WeeklyReportEmailTests {

	@Test
	void aWeekRunsSundayToSaturdayAndTheDefaultIsTheLastOneFinished() {
		// Sent Sunday 25 Oct 2026 at 00:00: the week just ended, Sun 18 – Sat 24.
		assertThat(WeeklyReportService.weekEnd(null, LocalDate.of(2026, 10, 25))).isEqualTo(LocalDate.of(2026, 10, 24));
		// On a Saturday the week is not over: the one before.
		assertThat(WeeklyReportService.weekEnd(null, LocalDate.of(2026, 10, 24))).isEqualTo(LocalDate.of(2026, 10, 17));
		// Any day asked for: the week holding it.
		assertThat(WeeklyReportService.weekEnd(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 28))).isEqualTo(LocalDate.of(2026, 10, 24));
		assertThat(WeeklyReportService.weekEnd(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 28))).isEqualTo(LocalDate.of(2026, 10, 24));
	}

	@Test
	void datesMoneyAndChangesReadAsInTheTemplate() {
		assertThat(WeeklyReportEmail.range(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 24))).isEqualTo("Sun 18 – Sat 24 Oct 2026");
		assertThat(WeeklyReportEmail.range(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 3))).isEqualTo("Sun 27 Sep – Sat 3 Oct 2026");
		assertThat(WeeklyReportEmail.range(LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 2))).isEqualTo("Sun 27 Dec 2026 – Sat 2 Jan 2027");
		assertThat(WeeklyReportEmail.money(new BigDecimal("1248.32"))).isEqualTo("$1,248.32");
		assertThat(WeeklyReportEmail.money(new BigDecimal("-360"))).isEqualTo("− $360.00");
		assertThat(WeeklyReportEmail.change(new BigDecimal("1248.32"), new BigDecimal("1110.40"), true)).contains("▲ 12.4%").contains("#15803d");
		assertThat(WeeklyReportEmail.change(new BigDecimal("90"), new BigDecimal("100"), true)).contains("▼ 10.0%").contains("#b91c1c");
		// Nothing to compare with: no percentage at all.
		assertThat(WeeklyReportEmail.change(new BigDecimal("50"), BigDecimal.ZERO, true)).isEmpty();
	}

	@Test
	void theEmailShowsTheWeekAndNoNameCanBreakIt() {
		WeeklyReport report = report(List.of(new WeeklyReport.Site("Easy IPTV", 2310, 2140)), 18);
		String html = WeeklyReportEmail.html(report, "https://app.test/");

		assertThat(WeeklyReportEmail.subject(report)).isEqualTo("One Base weekly report · Sun 18 – Sat 24 Oct 2026 · Net $1,248.32");
		assertThat(html).contains("Weekly report").contains("Sun 18 – Sat 24 Oct 2026 · Week 43").contains("$1,248.32")
			.contains("Easy IPTV").contains("2,310 sessions").contains("https://app.test/Logo.png")
			.contains("dashboard?range=custom&amp;from=2026-10-18&amp;to=2026-10-24");
		// In dark mode the ticket stays light and the page follows; as wide as the phone up to 480 px.
		assertThat(html).contains(".ob-card{background:#ffffff!important").contains(".ob-page,.ob-hole{background:#121212!important}").contains("max-width:480px")
			.doesNotContain("width=\"480\"").contains("@media (max-width:520px)");
		// A client named like a script is shown as text.
		assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
	}

	@Test
	void gmailOrGoogleAnalyticsNotAnsweringLeavesTheirPartNotAvailable() {
		String html = WeeklyReportEmail.html(report(null, null), "https://app.test");
		assertThat(html).contains("Google Analytics did not answer").contains("not available");
		// No brand with GA4 at all: no website section.
		assertThat(WeeklyReportEmail.html(report(List.of(), 3), "https://app.test")).doesNotContain("Website visits");
	}

	private static WeeklyReport report(List<WeeklyReport.Site> sites, Integer emails) {
		Money money = new Money(12, 7, new BigDecimal("1015.00"), 5, new BigDecimal("705.00"), new BigDecimal("1720.00"),
			new BigDecimal("360.00"), new BigDecimal("111.68"), new BigDecimal("1248.32"), new BigDecimal("143.33"));
		Money before = new Money(10, 5, new BigDecimal("800"), 5, new BigDecimal("700"), new BigDecimal("1500"),
			new BigDecimal("300"), new BigDecimal("89.60"), new BigDecimal("1110.40"), new BigDecimal("150"));
		return new WeeklyReport(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 17),
			43, money, before,
			List.of(new Line("Easy IPTV", 8, new BigDecimal("1180")), new Line("IPTV Now", 4, new BigDecimal("540"))),
			List.of(new Line("PayPal · main", 6, new BigDecimal("900"))),
			new Clients(9, 7, 2, 6, 48, 45, 1),
			new Attention(1, 1, List.of(new Person("<script>alert(1)</script>", "Easy IPTV", LocalDate.of(2026, 10, 28), false),
				new Person("Jane", null, null, true)), 0),
			new Credit(1240, 360, 300, new BigDecimal("360")),
			new Activity(134, 21, emails, List.of(new Line("R8den", 8, new BigDecimal("1100")))),
			sites, Instant.parse("2026-10-25T04:00:00Z"));
	}
}
