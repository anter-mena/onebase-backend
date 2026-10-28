package com.onebase.credit;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * The "panel credit is running low" email (2026-10-28), cut like the weekly report: the
 * payment receipt's ticket in tables and inline styles: the ticket stays light in dark
 * mode, the page around it follows the reader; as wide as the phone up to 480 px. Every name is escaped.
 */
public final class LowCreditEmail {

	private static final String PAGE = "#f4f4f5";
	private static final String INK = "#18181b";
	private static final String MUTED = "#71717a";
	private static final String FAINT = "#a1a1aa";
	private static final String RULE = "#e4e4e7";
	private static final String AMBER = "#b45309";
	private static final String BAD = "#b91c1c";
	private static final String FONT = "-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
	private static final String HEAD_STYLES = "<meta name=\"color-scheme\" content=\"light dark\">"
		+ "<meta name=\"supported-color-schemes\" content=\"light dark\">"
		+ "<style>@media (max-width:520px){.page{padding:12px 6px!important}.px{padding-left:16px!important;padding-right:16px!important}"
		+ ".big{font-size:30px!important}}</style>"
		+ "<style>:root{color-scheme:light dark;supported-color-schemes:light dark}"
		+ "@media (prefers-color-scheme:dark){.ob-page,.ob-hole{background:#121212!important}.ob-card{background:#ffffff!important;color:#18181b!important}}"
		+ "[data-ogsc] .ob-card,[data-ogsb] .ob-card{background:#ffffff!important;color:#18181b!important}</style>";

	private LowCreditEmail() {
	}

	/**
	 * What the email says.
	 *
	 * @param usedLastWeek credits spent in the last 7 days, for the pace
	 * @param lastTopupOn null when there has never been a top-up
	 */
	public record Facts(long left, int threshold, String clientName, String plan, int paymentCredits, String addedBy,
			long usedLastWeek, LocalDate lastTopupOn, long lastTopupCredits) {

		/** Days the credit lasts at last week's pace; null without a pace, or once it is gone. */
		public Long daysLeft() {
			if (left <= 0 || usedLastWeek <= 0) return null;
			return Math.max(0, Math.round(left / (usedLastWeek / 7.0)));
		}
	}

	public static String subject(Facts f) {
		return f.left() <= 0
			? "One Base · Panel credit is out (" + count(f.left()) + " credits)"
			: "One Base · Panel credit low: " + count(f.left()) + " credits left";
	}

	public static String text(Facts f, String appUrl) {
		StringBuilder t = new StringBuilder();
		t.append(f.left() <= 0 ? "The panel credit is out: " : "The panel credit is running low: ").append(count(f.left())).append(" credits left")
			.append(" (warning at ").append(f.threshold()).append(").\n\n")
			.append("It went below with ").append(f.clientName()).append("'s payment (").append(f.plan()).append(", ")
			.append(f.paymentCredits()).append(" credits), added by ").append(f.addedBy()).append(".\n")
			.append("Used in the last 7 days: ").append(count(f.usedLastWeek())).append(" credits");
		if (f.daysLeft() != null) t.append(", so about ").append(f.daysLeft()).append(" days left at that pace");
		t.append(".\n");
		if (f.lastTopupOn() != null) t.append("Last top-up: ").append(count(f.lastTopupCredits())).append(" credits on ").append(f.lastTopupOn().format(DAY)).append(".\n");
		t.append("\nTop up: ").append(link(appUrl)).append("\n");
		return t.toString();
	}

	public static String html(Facts f, String appAddress) {
		String appUrl = appAddress.replaceAll("/+$", "");
		boolean out = f.left() <= 0;
		StringBuilder h = new StringBuilder(6_000);
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
			.append(HEAD_STYLES).append("<title>").append(esc(subject(f))).append("</title></head>")
			.append("<body class=\"ob-page\" style=\"margin:0;padding:0;background:").append(PAGE).append(";\">")
			.append("<table role=\"presentation\" width=\"100%\" class=\"ob-page\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:").append(PAGE).append(";\">")
			.append("<tr><td align=\"center\" class=\"page\" style=\"padding:32px 12px;\">")
			.append("<table role=\"presentation\" width=\"100%\" class=\"ob-card\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:480px;background:#ffffff;border:1px solid ")
			.append(RULE).append(";border-radius:14px;font-family:").append(FONT).append(";color:").append(INK).append(";\">");

		// Header
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:28px 24px 18px;\">")
			.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td style=\"border:1px solid ").append(RULE)
			.append(";border-radius:10px;padding:8px;background:#ffffff;\"><img src=\"").append(esc(appUrl)).append("/Logo.png\" width=\"28\" height=\"28\" alt=\"One Base\" style=\"display:block;border:0;\"></td></tr></table>")
			.append("<div style=\"margin-top:14px;font-size:17px;font-weight:600;letter-spacing:-0.01em;\">").append(out ? "Panel credit is out" : "Panel credit is running low").append("</div>")
			.append("<div style=\"margin-top:3px;font-size:12px;color:").append(MUTED).append(";\">The warning is set at ").append(f.threshold()).append(" credits</div></td></tr>");

		// Tear line
		h.append("<tr><td style=\"padding:0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
			.append("<td width=\"11\" class=\"ob-hole\" style=\"width:11px;height:22px;background:").append(PAGE).append(";border-radius:0 11px 11px 0;font-size:0;line-height:0;\">&nbsp;</td>")
			.append("<td style=\"padding:0 8px;\"><div style=\"border-top:1px dashed #d4d4d8;font-size:0;line-height:0;\">&nbsp;</div></td>")
			.append("<td width=\"11\" class=\"ob-hole\" style=\"width:11px;height:22px;background:").append(PAGE).append(";border-radius:11px 0 0 11px;font-size:0;line-height:0;\">&nbsp;</td>")
			.append("</tr></table></td></tr>");

		// The number
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:20px 24px 4px;\">")
			.append("<div style=\"font-size:12px;color:").append(MUTED).append(";\">Credits left</div>")
			.append("<div class=\"big\" style=\"margin-top:4px;font-size:36px;font-weight:700;letter-spacing:-0.02em;font-variant-numeric:tabular-nums;color:")
			.append(out ? BAD : AMBER).append(";\">").append(count(f.left())).append("</div>");
		if (f.daysLeft() != null) {
			h.append("<div style=\"margin-top:4px;font-size:12px;color:").append(MUTED).append(";\">About <b style=\"color:").append(INK).append(";\">")
				.append(f.daysLeft()).append(f.daysLeft() == 1 ? " day" : " days").append("</b> left at last week's pace</div>");
		} else if (out) {
			h.append("<div style=\"margin-top:4px;font-size:12px;color:").append(BAD).append(";\">New plans still save, but the panel has nothing left to give.</div>");
		}
		h.append("</td></tr>");

		// Details
		h.append("<tr><td class=\"px\" style=\"padding:18px 24px 4px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"font-size:13px;font-variant-numeric:tabular-nums;\">");
		row(h, "Went below with", esc(f.clientName()) + " <span style=\"color:" + FAINT + ";font-size:12px;\">· " + esc(f.plan()) + "</span>");
		row(h, "That payment used", count(f.paymentCredits()) + " credits");
		row(h, "Added by", esc(f.addedBy()));
		row(h, "Used in the last 7 days", count(f.usedLastWeek()) + " credits");
		row(h, "Last top-up", f.lastTopupOn() == null ? "<span style=\"color:" + FAINT + ";\">none yet</span>"
			: count(f.lastTopupCredits()) + " credits <span style=\"color:" + FAINT + ";font-size:12px;\">· " + f.lastTopupOn().format(DAY) + "</span>");
		h.append("</table></td></tr>");

		// Button and small print
		h.append("<tr><td align=\"center\" class=\"px\" style=\"padding:22px 24px 6px;\"><a href=\"").append(esc(link(appUrl)))
			.append("\" style=\"display:inline-block;background:").append(INK).append(";color:#ffffff;text-decoration:none;font-size:13px;font-weight:500;border-radius:8px;padding:10px 18px;\">Top up the panel credit</a></td></tr>")
			.append("<tr><td align=\"center\" class=\"px\" style=\"padding:10px 24px 24px;font-size:11px;line-height:1.6;color:").append(FAINT)
			.append(";\">Sent once each time the credit goes below ").append(f.threshold()).append(". It warns again after a top-up brings it back above.</td></tr>")
			.append("</table></td></tr></table></body></html>");
		return h.toString();
	}

	private static String link(String appUrl) {
		return appUrl.replaceAll("/+$", "") + "/configuration?tab=expenses";
	}

	private static String count(long value) {
		return NumberFormat.getIntegerInstance(Locale.US).format(value);
	}

	private static String esc(String text) {
		return HtmlUtils.htmlEscape(text == null ? "" : text);
	}

	private static void row(StringBuilder h, String label, String valueHtml) {
		h.append("<tr><td style=\"padding:4px 0;color:").append(MUTED).append(";\">").append(esc(label))
			.append("</td><td align=\"right\" style=\"padding:4px 0;\">").append(valueHtml).append("</td></tr>");
	}
}
