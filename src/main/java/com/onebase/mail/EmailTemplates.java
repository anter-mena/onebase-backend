package com.onebase.mail;

import com.onebase.user.UserRole;
import org.springframework.web.util.HtmlUtils;

/**
 * What One Base's emails say and look like.
 *
 * <p>The design was made and approved on the frontend previews
 * ({@code onebase-frontend/public/preview/emails/*.html}); this class reproduces
 * them with the real values. Change the preview first, get it approved, then
 * mirror it here — not the other way round.
 *
 * <p>Every email goes out as two versions in one message: this HTML, and plain
 * text for mail apps that do not show HTML (it also helps with spam filters).
 * The HTML uses tables and inline styles only — the one thing Gmail, Outlook and
 * phone apps all agree on.
 *
 * <p>Images (the logo, the reset illustration) are loaded from the frontend's
 * public folder by full address: an email has no site of its own to load from.
 *
 * <p>⚠️ Everything a person typed — a name, the inviter's message — is escaped
 * before it goes into the HTML, so nobody can put markup in someone's inbox.
 */
public final class EmailTemplates {

	private EmailTemplates() {
	}

	/** One email: its subject, and the same content as plain text and as HTML. */
	public record Email(String subject, String text, String html) {
	}

	private static final String TEXT = "font-size:14px;line-height:1.5;";
	private static final String NOTE = "margin-top:8px;font-size:12px;line-height:1.4;color:#71717a;";

	// ── The two emails ──────────────────────────────────────────────────────

	public static Email invitation(String invitedBy, UserRole role, String message, String link, String assetBase) {
		String article = role == UserRole.ADMIN ? "an" : "a";
		String roleName = role == UserRole.ADMIN ? "Admin" : "Commercial";
		boolean hasMessage = message != null && !message.isBlank();

		String text = """
			Hello,

			%s invited you to join One Base as %s %s.
			%s
			Open this link to choose your name and password (it works once, for 7 days):

			%s

			If you weren't expecting this, you can ignore this email.
			""".formatted(invitedBy, article, roleName,
				hasMessage ? "\n" + invitedBy + " wrote:\n\"" + message.trim() + "\"\n" : "", link);

		String name = "<strong>" + escape(invitedBy) + "</strong>";
		String quote = hasMessage
			? paragraph(name + " wrote:<br>&quot;" + escape(message.trim()).replace("\n", "<br>") + "&quot;")
			: "";
		String card = """
			<div style="text-align:left;%s">%s%s%s</div>
			<p style="margin:0;text-align:center;%s">Click the button to choose your name and password (it works once, for <strong>7&nbsp;days</strong>):</p>
			%s
			<div style="%s">If you weren&#39;t expecting this, you can ignore this email.</div>"""
			.formatted(TEXT,
				paragraph("Hello,"),
				paragraph(name + " invited you to join One&nbsp;Base as " + article + " <strong>" + roleName + "</strong>."),
				quote,
				TEXT,
				button(link, "Join One Base", 32),
				NOTE);

		String html = page(560, card, "You are receiving this email because someone invited you to join One Base.", assetBase);
		return new Email("You're invited to One Base", text, html);
	}

	public static Email passwordReset(String link, String assetBase) {
		String text = """
			Someone asked to reset the password of your One Base account.
			Open this link to choose a new one (it works once, for 30 minutes):

			%s

			If it wasn't you, ignore this email — your password stays the same.
			""".formatted(link);

		String card = """
			<img src="%s/forgot-password.png" width="260" alt="" style="display:block;width:100%%;max-width:260px;height:auto;margin:22px auto 37px auto;border:0;outline:none;text-decoration:none;">
			<p style="margin:0;text-align:center;font-size:13px;line-height:1.5;">Someone asked to reset the password of your<br>One&nbsp;Base account. Click the button to choose<br>a new one (it works once, for <strong>30&nbsp;minutes</strong>):</p>
			%s
			<div style="%s">If it wasn&#39;t you, ignore this email &mdash; your password stays the same.</div>"""
			.formatted(escape(assetBase), button(link, "Choose a new password", 20), NOTE);

		String html = page(420, card,
			"You are receiving this email because a password reset<br>was requested for your One Base account.", assetBase);
		return new Email("Reset your One Base password", text, html);
	}

	// ── The shared frame ────────────────────────────────────────────────────

	/** Grey background, the logo, the white card with sharp corners, the footer under it. */
	private static String page(int width, String card, String whyLine, String assetBase) {
		String link = "<a href=\"#\" style=\"color:#71717a;text-decoration:underline;\">%s</a>";
		return """
			<!doctype html>
			<html lang="en">
			<head>
			  <meta charset="utf-8">
			  <meta name="viewport" content="width=device-width, initial-scale=1">
			  <meta name="color-scheme" content="light">
			</head>
			<body style="margin:0;padding:0;background:#f4f4f5;color:#202124;font-family:Arial,Helvetica,sans-serif;">
			  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:#f4f4f5;">
			    <tr><td align="center" style="padding:24px 16px;">
			      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:%dpx;">
			        <tr><td align="center" style="padding:0 0 20px 0;">
			          <img src="%s/Logo.png" width="40" height="40" alt="One Base" style="display:block;width:40px;height:40px;border:0;outline:none;text-decoration:none;">
			        </td></tr>
			        <tr><td style="background:#ffffff;border:1px solid #e4e4e7;padding:32px 28px;text-align:center;">
			          %s
			        </td></tr>
			        <tr><td style="padding:16px 8px 0 8px;text-align:center;font-size:11px;line-height:1.6;color:#71717a;">
			          %s<br>
			          &copy; 2026 One Base &middot; %s &middot; %s &middot; %s
			        </td></tr>
			      </table>
			    </td></tr>
			  </table>
			</body>
			</html>
			""".formatted(width, escape(assetBase), card, whyLine,
				link.formatted("Privacy Policy"), link.formatted("Terms of Use"), link.formatted("Help Center"));
	}

	/** The app's white button skin, inline. Gmail drops shadows and Outlook gradients; the border keeps it a button. */
	private static String button(String href, String label, int spaceAbove) {
		return """
			<table role="presentation" align="center" cellpadding="0" cellspacing="0" border="0" style="margin:%dpx auto 0 auto;"><tr><td style="background:#fafafa;border-radius:8px;border:1px solid #e4e4e7;"><a href="%s" style="display:inline-block;padding:8px 16px;font-size:13px;font-weight:600;color:#000000;text-decoration:none;border-radius:8px;background-color:#fafafa;background-image:linear-gradient(180deg, transparent 70.48%%, #ffffff 93.62%%, transparent 100%%);box-shadow:rgba(0,0,0,0.08) 0 -2px 1px 0 inset, rgba(255,255,255,0.5) 0 2px 1px 0 inset, 0 2px 5px -1px rgba(0,0,0,0.05), 0 1px 3px -1px rgba(0,0,0,0.3);text-shadow:0 1px 1px rgba(0,0,0,0.12);">%s</a></td></tr></table>"""
			.formatted(spaceAbove, escape(href), escape(label));
	}

	/** One block of the left-aligned intro, with a blank line's worth of space under it. */
	private static String paragraph(String html) {
		return "<p style=\"margin:0 0 21px 0;" + TEXT + "\">" + html + "</p>";
	}

	private static String escape(String value) {
		return HtmlUtils.htmlEscape(value == null ? "" : value);
	}
}
