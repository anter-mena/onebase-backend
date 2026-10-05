package com.onebase.inbox;

import com.onebase.inbox.GmailMailbox.Addr;
import com.onebase.inbox.GmailMailbox.Snapshot;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.UnsupportedEncodingException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the emails One Base sends: new, reply, forward, and drafts.
 *
 * <p>Plain text, like the reading pane. A reply quotes the original under
 * "On …, … wrote:" with {@code > } lines and carries In-Reply-To / References, so
 * Gmail and the client's mail app keep it in the same conversation. A forward
 * copies the original's header block and text, and its files when kept.
 */
final class MailComposer {

	private static final DateTimeFormatter QUOTE_DATE =
		DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	record FileData(String name, String contentType, byte[] bytes) {
	}

	/**
	 * Who the email is from, for its design: the brand's name, website and logo (an
	 * absolute https address, so mail apps can load it). Website and logo may be null.
	 */
	record Branding(String name, String website, String logoUrl) {
	}

	record Outgoing(
			InternetAddress from,
			List<InternetAddress> to,
			List<InternetAddress> cc,
			String subject,
			String body,
			String inReplyTo,
			String references,
			List<FileData> files,
			Branding branding) {
	}

	private MailComposer() {
	}

	static MimeMessage build(Session session, Outgoing mail) throws MessagingException {
		MimeMessage message = new MimeMessage(session);
		message.setFrom(mail.from());
		message.setRecipients(Message.RecipientType.TO, mail.to().toArray(InternetAddress[]::new));
		if (!mail.cc().isEmpty()) {
			message.setRecipients(Message.RecipientType.CC, mail.cc().toArray(InternetAddress[]::new));
		}
		message.setSubject(mail.subject(), "UTF-8");
		message.setSentDate(new Date());
		if (mail.inReplyTo() != null && !mail.inReplyTo().isBlank()) {
			message.setHeader("In-Reply-To", mail.inReplyTo());
		}
		if (mail.references() != null && !mail.references().isBlank()) {
			message.setHeader("References", mail.references());
		}
		// Plain text for every mail app, and the same words as HTML so the quoted original shows
		// as a grey quote block (no "> " at the start of each line) where HTML is shown.
		MimeMultipart alternative = new MimeMultipart("alternative");
		MimeBodyPart plain = new MimeBodyPart();
		plain.setText(mail.body(), "UTF-8");
		alternative.addBodyPart(plain);
		MimeBodyPart html = new MimeBodyPart();
		html.setText(branded(toHtml(mail.body()), mail.branding()), "UTF-8", "html");
		alternative.addBodyPart(html);
		if (mail.files().isEmpty()) {
			message.setContent(alternative);
		} else {
			MimeMultipart mixed = new MimeMultipart("mixed");
			MimeBodyPart text = new MimeBodyPart();
			text.setContent(alternative);
			mixed.addBodyPart(text);
			for (FileData file : mail.files()) {
				MimeBodyPart part = new MimeBodyPart();
				String type = file.contentType() == null || file.contentType().isBlank() ? "application/octet-stream" : file.contentType();
				part.setDataHandler(new DataHandler(new ByteArrayDataSource(file.bytes(), type)));
				try {
					part.setFileName(MimeUtility.encodeText(file.name(), "UTF-8", null));
				} catch (UnsupportedEncodingException e) {
					part.setFileName(file.name());
				}
				part.setDisposition(MimeBodyPart.ATTACHMENT);
				mixed.addBodyPart(part);
			}
			message.setContent(mixed);
		}
		message.saveChanges();
		return message;
	}

	/**
	 * Plain text → simple HTML: escaped, line breaks kept, and each run of "> " lines a
	 * blockquote (nested for "> > "), styled like Gmail's own quotes.
	 */
	static String toHtml(String text) {
		StringBuilder out = new StringBuilder("<div dir=\"auto\" style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.5;\">");
		int depth = 0;
		for (String line : (text == null ? "" : text).split("\n", -1)) {
			int level = 0;
			String rest = line;
			while (rest.startsWith(">")) {
				level++;
				rest = rest.substring(1);
				if (rest.startsWith(" ")) rest = rest.substring(1);
			}
			while (depth < level) {
				out.append("<blockquote class=\"gmail_quote\" style=\"margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex;color:#555;\">");
				depth++;
			}
			while (depth > level) {
				out.append("</blockquote>");
				depth--;
			}
			out.append(escapeHtml(rest)).append("<br>");
		}
		while (depth-- > 0) out.append("</blockquote>");
		return out.append("</div>").toString();
	}

	/**
	 * The words inside the same design as the One Base invitation and the website contact
	 * emails: the brand's logo on top, one white card, a small grey footer.
	 */
	static String branded(String bodyHtml, Branding brand) {
		String name = brand == null || brand.name() == null ? "" : brand.name();
		String logo;
		if (brand != null && brand.logoUrl() != null) {
			logo = "<img src=\"" + escapeHtml(brand.logoUrl()) + "\" width=\"40\" height=\"40\" alt=\"" + escapeHtml(name)
				+ "\" style=\"display:block;width:40px;height:40px;border:0;outline:none;text-decoration:none;\">";
		} else if (!name.isBlank()) {
			// No logo: the name, set in text, shows in every mail app with images on or off.
			logo = "<table role=\"presentation\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
				+ "<td style=\"background:#0a0a0a;border-radius:8px;padding:9px 12px;font-size:14px;line-height:1;font-weight:800;color:#ffffff;\">"
				+ escapeHtml(name) + "</td></tr></table>";
		} else {
			logo = "";
		}
		String site = brand == null || brand.website() == null ? null : brand.website();
		String footer = "&copy; " + java.time.Year.now(java.time.ZoneOffset.UTC) + (name.isBlank() ? "" : " " + escapeHtml(name))
			+ (site == null ? "" : " &middot; <a href=\"https://" + escapeHtml(site) + "\" style=\"color:#71717a;text-decoration:underline;\">"
				+ escapeHtml(site) + "</a>");
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
			      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;">
			        %s
			        <tr><td style="background:#ffffff;border:1px solid #e4e4e7;padding:28px 28px;text-align:left;">
			          %s
			        </td></tr>
			        <tr><td style="padding:16px 8px 0 8px;text-align:center;font-size:11px;line-height:1.6;color:#71717a;">%s</td></tr>
			      </table>
			    </td></tr>
			  </table>
			</body>
			</html>
			""".formatted(logo.isEmpty() ? "" : "<tr><td align=\"center\" style=\"padding:0 0 20px 0;\">" + logo + "</td></tr>", bodyHtml, footer);
	}

	private static String escapeHtml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static String replySubject(String subject) {
		String s = subject == null ? "" : subject.trim();
		return s.regionMatches(true, 0, "re:", 0, 3) ? s : "Re: " + s;
	}

	static String forwardSubject(String subject) {
		String s = subject == null ? "" : subject.trim();
		return s.regionMatches(true, 0, "fwd:", 0, 4) || s.regionMatches(true, 0, "fw:", 0, 3) ? s : "Fwd: " + s;
	}

	/** The original, quoted under the reply. */
	static String withQuote(String body, Snapshot original) {
		String when = original.date() == null ? "" : "On " + QUOTE_DATE.format(original.date()) + ", ";
		String quoted = original.content().text().lines().map(line -> line.isEmpty() ? ">" : "> " + line)
			.collect(Collectors.joining("\n"));
		return trimEnd(body) + "\n\n" + when + who(original.fromName(), original.fromEmail()) + " wrote:\n" + quoted + "\n";
	}

	/** The original's header block and text, under the forward. */
	static String withForward(String body, Snapshot original) {
		StringBuilder out = new StringBuilder(trimEnd(body)).append("\n\n---------- Forwarded message ---------\n");
		out.append("From: ").append(who(original.fromName(), original.fromEmail())).append('\n');
		if (original.date() != null) out.append("Date: ").append(QUOTE_DATE.format(original.date())).append('\n');
		out.append("Subject: ").append(original.subject()).append('\n');
		if (!original.to().isEmpty()) out.append("To: ").append(list(original.to())).append('\n');
		if (!original.cc().isEmpty()) out.append("Cc: ").append(list(original.cc())).append('\n');
		out.append('\n').append(original.content().text()).append('\n');
		return out.toString();
	}

	/** The References chain for a reply: the original's chain plus the original itself. */
	static String references(Snapshot original) {
		String chain = original.references() == null ? "" : original.references().trim();
		String own = original.messageIdHeader() == null ? "" : original.messageIdHeader().trim();
		return (chain + " " + own).trim();
	}

	private static String who(String name, String email) {
		return name == null || name.isBlank() ? "<" + email + ">" : name + " <" + email + ">";
	}

	private static String list(List<Addr> addrs) {
		return addrs.stream().map(a -> who(a.name(), a.email())).collect(Collectors.joining(", "));
	}

	private static String trimEnd(String text) {
		return text == null ? "" : text.stripTrailing();
	}
}
