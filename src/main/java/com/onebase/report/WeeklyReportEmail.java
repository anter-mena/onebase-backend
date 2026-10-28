package com.onebase.report;

import com.onebase.report.WeeklyReport.Line;
import com.onebase.report.WeeklyReport.Money;
import com.onebase.report.WeeklyReport.Person;
import com.onebase.report.WeeklyReport.Site;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * The weekly report as an email (template approved 2026-10-28): the payment receipt's
 * ticket — header, tear line with two notches, label/value rows, the Net in large, a
 * scalloped foot — rebuilt from what email clients draw: tables and inline styles, no
 * CSS masks, no SVG, the logo as a PNG on the app's own site. Every name is escaped.
 */
public final class WeeklyReportEmail {

	private static final String PAGE = "#f4f4f5";
	private static final String INK = "#18181b";
	private static final String MUTED = "#71717a";
	private static final String FAINT = "#a1a1aa";
	private static final String RULE = "#e4e4e7";
	private static final String GOOD = "#15803d";
	private static final String BAD = "#b91c1c";
	private static final String FONT = "-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	/**
	 * Light, whatever the reader's mode (2026-10-28): the colour-scheme signals that Apple
	 * Mail, iOS Mail and Outlook honour, and Outlook.com's own dark-mode markers. Then the
	 * phone layout: narrower sides and Net. Two blocks, because Gmail throws away a whole
	 * block over one rule it does not support.
	 */
	private static final String HEAD_STYLES = "<meta name=\"color-scheme\" content=\"light only\">"
		+ "<meta name=\"supported-color-schemes\" content=\"light\">"
		+ "<style>@media (max-width:520px){.page{padding:12px 6px!important}.px{padding-left:16px!important;padding-right:16px!important}"
		+ ".net{font-size:28px!important}}</style>"
		+ "<style>:root{color-scheme:light only;supported-color-schemes:light}"
		+ "@media (prefers-color-scheme:dark){.ob-page{background:#f4f4f5!important}.ob-card{background:#ffffff!important;color:#18181b!important}}"
		+ "[data-ogsc] .ob-card,[data-ogsb] .ob-card{background:#ffffff!important;color:#18181b!important}"
		+ "[data-ogsb] .ob-page{background:#f4f4f5!important}</style>";

	private WeeklyReportEmail() {
	}

	/** "One Base weekly report · Sun 19 – Sat 25 Oct 2026 · Net $1,248.32" */
	public static String subject(WeeklyReport r) {
		return "One Base weekly report · " + range(r.start(), r.end()) + " · Net " + money(r.money().net());
	}

	/** "Sun 19 – Sat 25 Oct 2026", "Sun 28 Sep – Sat 4 Oct 2026", "Sun 28 Dec 2025 – Sat 3 Jan 2026". */
	static String range(LocalDate start, LocalDate end) {
		if (start.getYear() != end.getYear()) return start.format(FULL) + " – " + end.format(FULL);
		if (start.getMonth() != end.getMonth()) return start.format(DAY_MONTH) + " – " + end.format(FULL);
		return start.format(DAY) + " – " + end.format(FULL);
	}

	static String money(BigDecimal value) {
		NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
		String text = format.format(value.abs());
		return value.signum() < 0 ? "− " + text : text;
	}

	private static String count(long value) {
		return NumberFormat.getIntegerInstance(Locale.US).format(value);
	}

	private static String esc(String text) {
		return HtmlUtils.htmlEscape(text == null ? "" : text);
	}

	/** "▲ 12.4%" in green when it went the good way, red when not; empty without a base to compare. */
	static String change(BigDecimal now, BigDecimal before, boolean upIsGood) {
		if (before == null || before.signum() == 0) return "";
		BigDecimal pct = now.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before.abs(), 1, RoundingMode.HALF_UP);
		if (pct.signum() == 0) return "<span style=\"color:" + MUTED + ";font-weight:600;\">= 0%</span>";
		boolean up = pct.signum() > 0;
		String color = up == upIsGood ? GOOD : BAD;
		return "<span style=\"color:" + color + ";font-weight:600;\">" + (up ? "▲ " : "▼ ") + pct.abs().toPlainString() + "%</span>";
	}

	public static String html(WeeklyReport r, String appAddress) {
		String appUrl = appAddress.replaceAll("/+$", "");
		StringBuilder h = new StringBuilder(12_000);
		Money m = r.money();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
			.append(HEAD_STYLES)
			.append("<title>").append(esc(subject(r))).append("</title></head>")
			.append("<body class=\"ob-page\" style=\"margin:0;padding:0;background:").append(PAGE).append(";color-scheme:light only;\">")
			.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:").append(PAGE).append(";\">")
			.append("<tr><td align=\"center\" class=\"page\" style=\"padding:32px 12px;\">")
			.append("<table role=\"presentation\" width=\"100%\" class=\"ob-card\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:480px;background:#ffffff;border:1px solid ")
			.append(RULE).append(";border-bottom:0;border-radius:14px 14px 0 0;font-family:").append(FONT).append(";color:").append(INK).append(";\">");

		// Header
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:28px 24px 22px;\">")
			.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"border:1px solid ").append(RULE)
			.append(";border-radius:10px;padding:8px;background:#ffffff;\"><img src=\"").append(esc(appUrl)).append("/Logo.png\" width=\"28\" height=\"28\" alt=\"One Base\" style=\"display:block;border:0;\"></td></tr></table>")
			.append("<div style=\"margin-top:14px;font-size:17px;font-weight:600;letter-spacing:-0.01em;\">Weekly report</div>")
			.append("<div style=\"margin-top:3px;font-size:12px;color:").append(MUTED).append(";\">").append(range(r.start(), r.end())).append(" · Week ").append(r.isoWeek()).append("</div>")
			.append("<div style=\"margin-top:2px;font-size:11px;color:").append(FAINT).append(";\">Compared with ")
			.append(r.previousStart().format(SHORT)).append(" – ").append(r.previousEnd().format(SHORT)).append("</div></td></tr>");

		// Tear line with its two notches
		h.append("<tr><td style=\"padding:0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
			.append("<td width=\"11\" style=\"width:11px;height:22px;background:").append(PAGE).append(";border-radius:0 11px 11px 0;font-size:0;line-height:0;\">&nbsp;</td>")
			.append("<td style=\"padding:0 8px;\"><div style=\"border-top:1px dashed #d4d4d8;font-size:0;line-height:0;\">&nbsp;</div></td>")
			.append("<td width=\"11\" style=\"width:11px;height:22px;background:").append(PAGE).append(";border-radius:11px 0 0 11px;font-size:0;line-height:0;\">&nbsp;</td>")
			.append("</tr></table></td></tr>");

		// Net
		String netChange = change(m.net(), r.previousMoney().net(), true);
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:20px 24px 6px;\">")
			.append("<div style=\"font-size:12px;color:").append(MUTED).append(";\">Net this week</div>")
			.append("<div class=\"net\" style=\"margin-top:4px;font-size:34px;font-weight:700;letter-spacing:-0.02em;font-variant-numeric:tabular-nums;")
			.append(m.net().signum() < 0 ? "color:" + BAD + ";" : "").append("\">").append(money(m.net())).append("</div>")
			.append("<div style=\"margin-top:4px;font-size:12px;\">").append(netChange.isEmpty() ? "" : netChange + " ")
			.append("<span style=\"color:").append(MUTED).append(";\">vs the week before (").append(money(r.previousMoney().net())).append(")</span></div></td></tr>");

		// Money
		section(h, "Money");
		row(h, "Money in · " + m.payments() + (m.payments() == 1 ? " payment" : " payments"), money(m.moneyIn()));
		sub(h, "New plans · " + m.newPlans(), money(m.newPlansAmount()));
		sub(h, "Renewals · " + m.renewals(), money(m.renewalsAmount()));
		row(h, "Panel credit top-ups", money(m.topups().negate()));
		row(h, "Perks paid", money(m.perks().negate()));
		h.append("<tr><td colspan=\"2\" style=\"padding:6px 0 0;\"><div style=\"border-top:1px solid ").append(FAINT).append(";border-bottom:1px solid ").append(FAINT)
			.append(";height:2px;font-size:0;line-height:0;\">&nbsp;</div></td></tr>")
			.append("<tr><td style=\"padding:8px 0 2px;font-weight:600;\">Net</td><td align=\"right\" style=\"padding:8px 0 2px;font-weight:700;color:")
			.append(m.net().signum() < 0 ? BAD : GOOD).append(";\">").append(money(m.net())).append("</td></tr>");
		sub(h, "Average payment", m.payments() == 0 ? "—" : money(m.average()));
		end(h);

		divider(h);
		section(h, "By brand");
		if (r.brands().isEmpty()) empty(h, "No payments this week.");
		for (Line line : r.brands()) named(h, line.name(), line.count() + (line.count() == 1 ? " payment" : " payments"), money(line.amount()));
		end(h);

		if (!r.accounts().isEmpty()) {
			section(h, "Paid to");
			for (Line line : r.accounts()) named(h, line.name(), null, money(line.amount()));
			end(h);
		}

		divider(h);
		WeeklyReport.Clients c = r.clients();
		section(h, "Clients");
		row(h, "New clients", c.newClients() + (c.newClients() == 0 ? "" : " <span style=\"color:" + FAINT + ";font-size:12px;\">(" + c.fromWhatsApp() + " WhatsApp · " + c.byHand() + " by hand)</span>"));
		row(h, "Became active (paid)", String.valueOf(c.becameActive()));
		long delta = c.activeNow() - c.activeBefore();
		String deltaText = delta == 0 ? "" : " <span style=\"color:" + (delta > 0 ? GOOD : BAD) + ";font-size:12px;font-weight:600;\">" + (delta > 0 ? "▲ " : "▼ ") + Math.abs(delta) + "</span>";
		row(h, "Active at the end of the week", c.activeNow() + deltaText);
		row(h, "Ended this week", String.valueOf(c.ended()));
		end(h);

		WeeklyReport.Attention a = r.attention();
		section(h, "Needs attention");
		h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">Ending in the next 10 days</td><td align=\"right\" style=\"padding:4px 0;font-weight:600;\">").append(a.endingSoon()).append("</td></tr>");
		h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">Callbacks waiting (trial over)</td><td align=\"right\" style=\"padding:4px 0;font-weight:600;\">").append(a.callbacks()).append("</td></tr>");
		end(h);
		if (!a.people().isEmpty()) {
			h.append("<tr><td class=\"px\" style=\"padding:0 24px 4px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:4px;font-size:12px;background:#fafafa;border:1px solid ")
				.append(PAGE).append(";border-radius:8px;\">")
				.append("<tr><td colspan=\"2\" style=\"padding:8px 10px 2px;color:").append(FAINT).append(";font-size:11px;\">Soonest first</td></tr>");
			// Read from the Sunday the report goes out.
			LocalDate sunday = r.end().plusDays(1);
			for (Person p : a.people()) {
				String when;
				String color = INK;
				if (p.callback()) when = "callback";
				else {
					when = (p.endsOn().isBefore(sunday) ? "ended " : "ends ") + p.endsOn().format(DAY_MONTH);
					if (!p.endsOn().isAfter(sunday.plusDays(2))) color = BAD;
				}
				h.append("<tr><td style=\"padding:3px 10px;\">").append(esc(p.name())).append(" <span style=\"color:").append(FAINT).append(";\">· ")
					.append(esc(p.brand() == null ? "—" : p.brand())).append("</span></td><td align=\"right\" style=\"padding:3px 10px;color:").append(color)
					.append(";\">").append(when).append("</td></tr>");
			}
			if (a.more() > 0) {
				h.append("<tr><td colspan=\"2\" style=\"padding:3px 10px;color:").append(FAINT).append(";\">and ").append(a.more()).append(" more in Renewals</td></tr>");
			}
			h.append("<tr><td colspan=\"2\" style=\"height:5px;font-size:0;line-height:0;\">&nbsp;</td></tr></table></td></tr>");
		}

		divider(h);
		WeeklyReport.Credit cr = r.credit();
		section(h, "Panel credit");
		h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">Credits left now</td><td align=\"right\" style=\"padding:4px 0;font-weight:600;")
			.append(cr.left() < 0 ? "color:" + BAD + ";" : "").append("\">").append(count(cr.left())).append("</td></tr>");
		row(h, "Used this week", count(cr.used()));
		row(h, "Topped up", cr.toppedUpCredits() == 0 ? "0" : count(cr.toppedUpCredits()) + " <span style=\"color:" + FAINT + ";font-size:12px;\">(" + money(cr.toppedUpAmount()) + ")</span>");
		end(h);

		divider(h);
		WeeklyReport.Activity act = r.activity();
		section(h, "Activity");
		row(h, "WhatsApp messages received", count(act.whatsappMessages()) + " <span style=\"color:" + FAINT + ";font-size:12px;\">· "
			+ act.whatsappConversations() + (act.whatsappConversations() == 1 ? " conversation" : " conversations") + "</span>");
		row(h, "Brand emails received", act.brandEmails() == null ? "<span style=\"color:" + FAINT + ";\">not available</span>" : count(act.brandEmails()));
		if (!act.paymentsBy().isEmpty()) {
			StringBuilder by = new StringBuilder();
			for (Line line : act.paymentsBy()) {
				if (!by.isEmpty()) by.append(" · ");
				by.append(esc(line.name())).append(' ').append(line.count());
			}
			row(h, "Payments added by", by.toString());
		}
		end(h);

		if (r.sites() == null || !r.sites().isEmpty()) {
			section(h, "Website visits");
			if (r.sites() == null) empty(h, "Not available this time: Google Analytics did not answer.");
			else for (Site s : r.sites()) {
				String ch = change(BigDecimal.valueOf(s.sessions()), BigDecimal.valueOf(s.previousSessions()), true);
				named(h, s.brand(), null, count(s.sessions()) + " sessions" + (ch.isEmpty() ? "" : " <span style=\"font-size:12px;\">" + ch + "</span>"));
			}
			end(h);
		}

		// Button and small print
		String link = appUrl + "/dashboard?range=custom&from=" + r.start() + "&to=" + r.end();
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:22px 24px 6px;\"><a href=\"").append(esc(link))
			.append("\" style=\"display:inline-block;background:").append(INK).append(";color:#ffffff;text-decoration:none;font-size:13px;font-weight:500;border-radius:8px;padding:10px 18px;\">Open this week in One Base</a></td></tr>")
			.append("<tr><td align=\"center\" class=\"px\" style=\"padding:10px 24px 26px;font-size:11px;line-height:1.6;color:").append(FAINT)
			.append(";\">Sent every Sunday at 00:00 (Toronto) · amounts in USD</td></tr></table>");

		// Scalloped foot
		h.append("<table role=\"presentation\" width=\"100%\" class=\"ob-card\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:480px;background:#ffffff;border-left:1px solid ")
			.append(RULE).append(";border-right:1px solid ").append(RULE).append(";\"><tr><td colspan=\"20\" style=\"font-size:0;line-height:0;padding:0;height:8px;\">&nbsp;</td></tr><tr>");
		for (int i = 0; i < 20; i++) {
			h.append("<td style=\"padding:0;font-size:0;line-height:0;\"><div style=\"height:7px;margin:0 3px;background:").append(PAGE)
				.append(";border-radius:7px 7px 0 0;\">&nbsp;</div></td>");
		}
		h.append("</tr></table></td></tr></table></body></html>");
		return h.toString();
	}

	private static void section(StringBuilder h, String title) {
		h.append("<tr><td class=\"px\" style=\"padding:14px 24px 4px;\"><div style=\"font-size:12px;font-weight:600;letter-spacing:0.04em;text-transform:uppercase;color:")
			.append(MUTED).append(";\">").append(esc(title)).append("</div>")
			.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:8px;font-size:13px;font-variant-numeric:tabular-nums;\">");
	}

	private static void end(StringBuilder h) {
		h.append("</table></td></tr>");
	}

	private static void divider(StringBuilder h) {
		h.append("<tr><td class=\"px\" style=\"padding:14px 24px 0;\"><div style=\"border-top:1px dashed ").append(RULE).append(";font-size:0;line-height:0;\">&nbsp;</div></td></tr>");
	}

	/** A label (plain text, escaped) and a value (already safe HTML). */
	private static void row(StringBuilder h, String label, String valueHtml) {
		h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">").append(esc(label))
			.append("</td><td align=\"right\" style=\"padding:4px 0;\">").append(valueHtml).append("</td></tr>");
	}

	private static void sub(StringBuilder h, String label, String value) {
		h.append("<tr><td style=\"padding:2px 0 2px 12px;color:").append(FAINT).append(";font-size:12px;\">").append(esc(label))
			.append("</td><td align=\"right\" style=\"padding:2px 0;color:").append(FAINT).append(";font-size:12px;\">").append(esc(value)).append("</td></tr>");
	}

	/** A name (escaped), an optional grey note, and a value (already safe HTML). */
	private static void named(StringBuilder h, String name, String note, String valueHtml) {
		h.append("<tr><td style=\"padding:4px 0;\">").append(esc(name));
		if (note != null) h.append(" <span style=\"color:").append(FAINT).append(";font-size:12px;\">· ").append(esc(note)).append("</span>");
		h.append("</td><td align=\"right\" style=\"padding:4px 0;\">").append(valueHtml).append("</td></tr>");
	}

	private static void empty(StringBuilder h, String text) {
		h.append("<tr><td colspan=\"2\" style=\"padding:4px 0;color:").append(FAINT).append(";\">").append(esc(text)).append("</td></tr>");
	}

}
