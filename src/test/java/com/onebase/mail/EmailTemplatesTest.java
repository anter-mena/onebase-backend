package com.onebase.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.onebase.user.UserRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The real email templates, without Spring or a database.
 *
 * <p>Also writes each one to {@code target/email-previews/} so it can be opened in
 * a browser and compared with the approved design in the frontend's previews.
 */
class EmailTemplatesTest {

	private static final String ASSETS = "https://app.test";
	private static final Path OUT = Path.of("target", "email-previews");

	@Test
	void invitationCarriesTheRealValues() throws IOException {
		var email = EmailTemplates.invitation("Youssef Alaoui", UserRole.COMMERCIAL, "Welcome to the team!",
			"https://app.test/accept-invite?token=abc", ASSETS);
		assertThat(email.subject()).isEqualTo("You're invited to One Base");
		assertThat(email.html())
			.contains("<strong>Youssef Alaoui</strong> invited you")
			.contains("as a <strong>Commercial</strong>")
			.contains("<strong>Youssef Alaoui</strong> wrote:")
			.contains("href=\"https://app.test/accept-invite?token=abc\"")
			.contains("src=\"https://app.test/Logo.png\"")
			.contains("max-width:560px");
		assertThat(email.text()).contains("https://app.test/accept-invite?token=abc").contains("as a Commercial");
		write("invitation.html", email.html());
	}

	@Test
	void adminInvitationSaysAnAdmin() {
		var email = EmailTemplates.invitation("Sara", UserRole.ADMIN, null, "https://app.test/x", ASSETS);
		assertThat(email.html()).contains("as an <strong>Admin</strong>");
		assertThat(email.text()).contains("as an Admin");
	}

	@Test
	void noMessageMeansNoQuote() {
		var email = EmailTemplates.invitation("Sara", UserRole.COMMERCIAL, "   ", "https://app.test/x", ASSETS);
		assertThat(email.html()).doesNotContain(" wrote:");
		assertThat(email.text()).doesNotContain(" wrote:");
	}

	@Test
	void whatPeopleTypeIsEscaped() {
		var email = EmailTemplates.invitation("<b>Eve</b>", UserRole.COMMERCIAL, "<script>alert(1)</script>\nline two",
			"https://app.test/x", ASSETS);
		assertThat(email.html())
			.doesNotContain("<script>").doesNotContain("<b>Eve</b>")
			.contains("&lt;script&gt;")
			.contains("line two"); // the message's line breaks become <br>
	}

	@Test
	void passwordResetUsesTheIllustrationAndTheLink() throws IOException {
		var email = EmailTemplates.passwordReset("https://app.test/reset-password?token=xyz", ASSETS);
		assertThat(email.subject()).isEqualTo("Reset your One Base password");
		assertThat(email.html())
			.contains("src=\"https://app.test/forgot-password.png\"")
			.contains("href=\"https://app.test/reset-password?token=xyz\"")
			.contains("Choose a new password")
			.contains("max-width:420px");
		assertThat(email.text()).contains("https://app.test/reset-password?token=xyz");
		write("password-reset.html", email.html());
	}

	private static void write(String name, String html) throws IOException {
		Files.createDirectories(OUT);
		Files.writeString(OUT.resolve(name), html, StandardCharsets.UTF_8);
	}
}
