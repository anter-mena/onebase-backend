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

	record Outgoing(
			InternetAddress from,
			List<InternetAddress> to,
			List<InternetAddress> cc,
			String subject,
			String body,
			String inReplyTo,
			String references,
			List<FileData> files) {
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
		if (mail.files().isEmpty()) {
			message.setText(mail.body(), "UTF-8");
		} else {
			MimeMultipart mixed = new MimeMultipart("mixed");
			MimeBodyPart text = new MimeBodyPart();
			text.setText(mail.body(), "UTF-8");
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
