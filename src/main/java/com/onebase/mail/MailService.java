package com.onebase.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the emails auth needs: the password-reset link and the invitation.
 *
 * <p>Spring only creates a {@link JavaMailSender} once {@code SPRING_MAIL_HOST}
 * is set. Until then there is nowhere to send mail, so the link is written to
 * the log instead, with a warning. That keeps the reset flow testable before
 * an email provider is chosen, and the warning makes the gap impossible to miss.
 *
 * <p>⚠️ Logging a live reset link is only acceptable while nobody but you can
 * read the server logs. Set up SMTP before inviting anyone (see BugForLater.md).
 */
@Service
public class MailService {

	private static final Logger log = LoggerFactory.getLogger(MailService.class);

	private final ObjectProvider<JavaMailSender> sender;
	private final String from;

	public MailService(ObjectProvider<JavaMailSender> sender, @Value("${onebase.mail.from}") String from) {
		this.sender = sender;
		this.from = from;
	}

	public void sendPasswordReset(String to, String name, String link) {
		String body = """
			Hi %s,

			Someone asked to reset the password of your One Base account.
			Open this link to choose a new one (it works once, for 30 minutes):

			%s

			If it wasn't you, ignore this email — your password stays the same.
			""".formatted(name, link);
		send(to, "Reset your One Base password", body, link);
	}

	/** The invitation. The inviter's optional message is quoted as they wrote it. */
	public void sendInvitation(String to, String invitedBy, String roleLabel, String message, String link) {
		String note = message == null || message.isBlank() ? "" : "\n%s wrote:\n\"%s\"\n".formatted(invitedBy, message.trim());
		String body = """
			Hello,

			%s invited you to join One Base as %s.
			%s
			Open this link to choose your name and password (it works once, for 7 days):

			%s

			If you weren't expecting this, you can ignore this email.
			""".formatted(invitedBy, roleLabel, note, link);
		send(to, "You're invited to One Base", body, link);
	}

	private void send(String to, String subject, String body, String link) {
		JavaMailSender mail = sender.getIfAvailable();
		if (mail == null) {
			log.warn("No mail server configured (SPRING_MAIL_HOST) — email to {} not sent. Link: {}", to, link);
			return;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		try {
			mail.send(message);
		} catch (RuntimeException e) {
			// Never tell the caller: "forgot password" must answer the same whether or not mail went out.
			log.error("Could not send \"{}\" to {}", subject, to, e);
		}
	}
}
