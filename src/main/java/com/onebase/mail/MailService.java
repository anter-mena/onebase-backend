package com.onebase.mail;

import com.onebase.mail.EmailTemplates.Email;
import com.onebase.user.UserRole;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends the emails auth needs: the password-reset link and the invitation.
 *
 * <p>Each one carries both versions from {@link EmailTemplates}: the designed
 * HTML and plain text. Images load from the frontend's public folder
 * ({@code onebase.frontend-url} + {@code /Logo.png}, {@code /forgot-password.png}).
 *
 * <p>Spring only creates a {@link JavaMailSender} once {@code SPRING_MAIL_HOST}
 * is set (today: Gmail). Without it there is nowhere to send mail, so the link
 * is written to the log instead, with a warning — the flow keeps working, and
 * the gap is impossible to miss.
 */
@Service
public class MailService {

	private static final Logger log = LoggerFactory.getLogger(MailService.class);

	private final ObjectProvider<JavaMailSender> sender;
	private final String from;
	private final String assetBase;

	public MailService(ObjectProvider<JavaMailSender> sender,
			@Value("${onebase.mail.from}") String from,
			@Value("${onebase.frontend-url}") String frontendUrl) {
		this.sender = sender;
		this.from = from;
		this.assetBase = frontendUrl.replaceAll("/+$", "");
	}

	public void sendPasswordReset(String to, String link) {
		send(to, EmailTemplates.passwordReset(link, assetBase), link);
	}

	/** The invitation. The inviter's optional message is quoted as they wrote it. */
	public void sendInvitation(String to, String invitedBy, UserRole role, String message, String link) {
		send(to, EmailTemplates.invitation(invitedBy, role, message, link, assetBase), link);
	}

	private void send(String to, Email email, String link) {
		JavaMailSender mail = sender.getIfAvailable();
		if (mail == null) {
			log.warn("No mail server configured (SPRING_MAIL_HOST) — email to {} not sent. Link: {}", to, link);
			return;
		}
		try {
			MimeMessage message = mail.createMimeMessage();
			// true = multipart: the plain-text and HTML versions travel together.
			MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
			helper.setFrom(from);
			helper.setTo(to);
			helper.setSubject(email.subject());
			helper.setText(email.text(), email.html());
			mail.send(message);
		} catch (MessagingException | MailException e) {
			// Never tell the caller: "forgot password" must answer the same whether or not mail went out.
			log.error("Could not send \"{}\" to {}", email.subject(), to, e);
		}
	}
}
