package com.onebase.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The low panel credit warning: when it fires, and what it says. */
class LowCreditTests {

	@Test
	void itWarnsOncePerCrossingOfTheMark() {
		assertThat(LowCredit.crossed(60, 50, 50)).isTrue();   // down to the mark
		assertThat(LowCredit.crossed(51, 12, 50)).isTrue();   // straight past it
		assertThat(LowCredit.crossed(55, -3, 50)).isTrue();   // past zero in one go
		assertThat(LowCredit.crossed(50, 40, 50)).isFalse();  // already low: said once already
		assertThat(LowCredit.crossed(200, 120, 50)).isFalse(); // still well above
	}

	@Test
	void theEmailSaysHowMuchIsLeftAndHowLongItLasts() {
		LowCreditEmail.Facts facts = new LowCreditEmail.Facts(42, 50, "<b>Jane</b>", "3 months · 2 devices", 12, "R8den",
			70, LocalDate.of(2026, 10, 16), 120);
		String html = LowCreditEmail.html(facts, "https://app.test/");

		assertThat(LowCreditEmail.subject(facts)).isEqualTo("One Base · Panel credit low: 42 credits left");
		assertThat(facts.daysLeft()).isEqualTo(4L); // 70 a week = 10 a day
		assertThat(html).contains("Panel credit is running low").contains(">42<").contains("About <b style=\"color:#18181b;\">4 days</b>")
			.contains("https://app.test/configuration?tab=expenses").contains("120 credits").contains("Fri 16 Oct 2026")
			.contains(".ob-card{background:#ffffff!important").contains("max-width:480px")
			.doesNotContain("<b>Jane</b>").contains("&lt;b&gt;Jane&lt;/b&gt;");
		assertThat(LowCreditEmail.text(facts, "https://app.test")).contains("42 credits left").contains("about 4 days left");
	}

	@Test
	void outOfCreditSaysSo() {
		LowCreditEmail.Facts facts = new LowCreditEmail.Facts(-3, 50, "Jane", "1 month · 1 device", 8, "R8den", 0, null, 0);
		assertThat(LowCreditEmail.subject(facts)).isEqualTo("One Base · Panel credit is out (-3 credits)");
		assertThat(facts.daysLeft()).isNull();
		assertThat(LowCreditEmail.html(facts, "https://app.test")).contains("Panel credit is out").contains("none yet");
	}
}
