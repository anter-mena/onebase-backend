package com.onebase.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.common.ApiException;
import com.onebase.inbox.GmailMailbox.Addr;
import com.onebase.inbox.GmailMailbox.FolderKey;
import com.onebase.inbox.GmailMailbox.Snapshot;
import com.onebase.inbox.InboxDtos.ComposeRequest;
import com.onebase.inbox.InboxDtos.MailDetail;
import com.onebase.inbox.InboxDtos.Overview;
import com.onebase.inbox.MailComposer.FileData;
import com.onebase.inbox.MailComposer.Outgoing;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** The Inbox without Gmail: reading emails, building answers, brands and the From rules. */
class InboxUnitTests {

	private static final Session SESSION = Session.getInstance(new Properties());

	// ── Reading an email ─────────────────────────────────────────────────

	@Test
	void anHtmlOnlyEmailIsShownAsTextWithItsLinks() throws Exception {
		MimeMessage m = new MimeMessage(SESSION);
		m.setContent("<html><head><style>p{color:red}</style></head><body><p>Hello <b>Jane</b>,</p>"
			+ "<p>Your code is ready.<br>Open <a href=\"https://easyiptv.ca/setup\">the guide</a>.</p>"
			+ "<script>alert(1)</script></body></html>", "text/html; charset=UTF-8");
		m.saveChanges();

		MailContent.Parsed parsed = MailContent.parse(m);

		assertThat(parsed.text()).isEqualTo("Hello Jane,\n\nYour code is ready.\nOpen the guide [https://easyiptv.ca/setup].");
		assertThat(parsed.text()).doesNotContain("alert", "color");
		assertThat(parsed.attachments()).isEmpty();
	}

	@Test
	void theTextVersionWinsAndFilesAreNumberedInOrder() throws Exception {
		MimeMultipart alternative = new MimeMultipart("alternative");
		alternative.addBodyPart(part("Plain words", "text/plain; charset=UTF-8", null));
		alternative.addBodyPart(part("<p>HTML words</p>", "text/html; charset=UTF-8", null));
		MimeBodyPart alternativePart = new MimeBodyPart();
		alternativePart.setContent(alternative);

		MimeMultipart mixed = new MimeMultipart("mixed");
		mixed.addBodyPart(alternativePart);
		mixed.addBodyPart(file("invoice.pdf", "application/pdf", "PDF-BYTES"));
		mixed.addBodyPart(file("photo.png", "image/png", "PNG-BYTES"));
		MimeMessage m = new MimeMessage(SESSION);
		m.setContent(mixed);
		m.saveChanges();

		MailContent.Parsed parsed = MailContent.parse(m);

		assertThat(parsed.text()).isEqualTo("Plain words");
		assertThat(parsed.attachments()).extracting(MailContent.Attachment::name).containsExactly("invoice.pdf", "photo.png");
		assertThat(parsed.attachments()).extracting(MailContent.Attachment::index).containsExactly(0, 1);
		assertThat(new String(MailContent.read(MailContent.attachmentPart(m, 1), 1000), StandardCharsets.UTF_8)).isEqualTo("PNG-BYTES");
		assertThat(MailContent.attachmentPart(m, 2)).isNull();
	}

	@Test
	void textIsTidied() {
		assertThat(MailContent.tidy("a  \r\n\r\n\r\n\r\nb \n")).isEqualTo("a\n\nb");
	}

	// ── Building an answer ──────────────────────────────────────────────

	@Test
	void aReplyQuotesTheOriginalAndStaysInTheConversation() {
		Snapshot original = snapshot("support@easyiptv.ca", List.of(new Addr("", "jane@example.com")),
			"Line one\n\nLine three", "[EasyIPTV] Help", List.of("EasyIPTV"));

		assertThat(MailComposer.replySubject("[EasyIPTV] Help")).isEqualTo("Re: [EasyIPTV] Help");
		assertThat(MailComposer.replySubject("RE: Help")).isEqualTo("RE: Help");
		assertThat(MailComposer.forwardSubject("Help")).isEqualTo("Fwd: Help");
		assertThat(MailComposer.withQuote("Thanks!\n", original))
			.isEqualTo("Thanks!\n\nOn Mon 5 Oct 2026 at 13:24 UTC, EasyIPTV Contact <support@easyiptv.ca> wrote:\n> Line one\n>\n> Line three\n");
		assertThat(MailComposer.references(original)).isEqualTo("<a@example.com> <b@easyiptv.ca>");
	}

	@Test
	void aBuiltEmailCarriesItsHeadersAndFiles() throws Exception {
		MimeMessage built = MailComposer.build(SESSION, new Outgoing(
			new InternetAddress("support@easyiptv.ca", "EasyIPTV Support", "UTF-8"),
			List.of(new InternetAddress("jane@example.com")), List.of(),
			"Re: Help", "Hello Jane", "<b@easyiptv.ca>", "<a@example.com> <b@easyiptv.ca>",
			List.of(new FileData("guide é.pdf", "application/pdf", "PDF".getBytes(StandardCharsets.UTF_8))), null));

		assertThat(built.getHeader("In-Reply-To")[0]).isEqualTo("<b@easyiptv.ca>");
		assertThat(built.getHeader("References")[0]).isEqualTo("<a@example.com> <b@easyiptv.ca>");
		assertThat(((InternetAddress) built.getFrom()[0]).getAddress()).isEqualTo("support@easyiptv.ca");
		MailContent.Parsed parsed = MailContent.parse(built);
		assertThat(parsed.text()).isEqualTo("Hello Jane");
		assertThat(parsed.attachments()).extracting(MailContent.Attachment::name).containsExactly("guide é.pdf");
	}

	// ── Brands, senders, the From rules ──────────────────────────────────

	@Test
	void aLabelMatchingABrandGivesItsLogoAndItsSupportAddress() {
		Fixture f = new Fixture();
		when(f.mailbox.counts()).thenReturn(Map.of(FolderKey.INBOX, 4, FolderKey.DRAFTS, 1, FolderKey.JUNK, 0));

		Overview overview = f.service.overview();

		assertThat(overview.brands()).extracting(InboxDtos.BrandView::name).containsExactly("EasyIPTV", "Personal");
		assertThat(overview.brands().get(0).sender()).isEqualTo("support@easyiptv.ca");
		assertThat(overview.brands().get(0).logoUrl()).startsWith("/api/brands/7/logo?v=");
		assertThat(overview.brands().get(1).sender()).isNull();
		assertThat(overview.senders()).extracting(InboxDtos.SenderView::email)
			.containsExactly("jamie.responde@gmail.com", "support@easyiptv.ca");
		assertThat(overview.folders()).extracting(InboxDtos.FolderView::unread).containsExactly(4, 1, 0, 0, 0, 0);
	}

	@Test
	void aFormEmailIsAnsweredToTheClientFromTheBrandAddress() {
		Fixture f = new Fixture();
		Snapshot form = new Snapshot(11, FolderKey.INBOX, "EasyIPTV Contact", "support@easyiptv.ca",
			List.of(new Addr("", "support@easyiptv.ca")), List.of(), List.of(new Addr("", "jane@example.com")), List.of(),
			"[EasyIPTV] New message", Instant.parse("2026-10-05T13:24:00Z"), false, false, List.of("EasyIPTV"), true,
			new MailContent.Parsed("Hi", List.of()), "<x@easyiptv.ca>", null, null);
		when(f.mailbox.get(FolderKey.INBOX, 11)).thenReturn(form);

		MailDetail detail = f.service.detail("inbox", "11", true);

		assertThat(detail.brand()).isEqualTo("EasyIPTV");
		assertThat(detail.replyRecipients()).containsExactly("jane@example.com");
		assertThat(detail.replyAllCc()).isEmpty();
		assertThat(detail.defaultFrom()).isEqualTo("support@easyiptv.ca");
		assertThat(detail.read()).isTrue();
		verify(f.mailbox).apply(FolderKey.INBOX, 11, GmailMailbox.Action.READ);
	}

	@Test
	void aClientWritingToASalesAddressIsAnsweredFromSupportAndCcStaysOutside() {
		Fixture f = new Fixture();
		Snapshot direct = new Snapshot(12, FolderKey.INBOX, "Jane", "jane@example.com",
			List.of(new Addr("", "sales@easyiptv.ca")), List.of(new Addr("Bob", "bob@example.com")), List.of(), List.of(),
			"Price?", Instant.parse("2026-10-05T13:24:00Z"), true, false, List.of(), false,
			new MailContent.Parsed("How much?", List.of()), "<y@example.com>", null, null);
		when(f.mailbox.get(FolderKey.INBOX, 12)).thenReturn(direct);

		MailDetail detail = f.service.detail("inbox", "12", true);

		// No label yet: the brand comes from the address it was sent to.
		assertThat(detail.brand()).isEqualTo("EasyIPTV");
		assertThat(detail.defaultFrom()).isEqualTo("support@easyiptv.ca");
		assertThat(detail.replyRecipients()).containsExactly("jane@example.com");
		assertThat(detail.replyAllCc()).containsExactly("bob@example.com");
		verify(f.mailbox, never()).apply(anyFolder(), anyLong(), eq(GmailMailbox.Action.READ));
	}

	@Test
	void sendingRefusesAnUnknownFromAndBadAddresses() {
		Fixture f = new Fixture();
		assertThatThrownBy(() -> f.service.send(compose("evil@example.com", List.of("jane@example.com")), List.of()))
			.isInstanceOf(ApiException.class).hasMessageContaining("From list");
		assertThatThrownBy(() -> f.service.send(compose("support@easyiptv.ca", List.of("not an address")), List.of()))
			.isInstanceOf(ApiException.class).hasMessageContaining("not a valid email address");
		assertThatThrownBy(() -> f.service.send(compose("support@easyiptv.ca", List.of()), List.of()))
			.isInstanceOf(ApiException.class).hasMessageContaining("at least one recipient");
		assertThatThrownBy(() -> f.service.list("spam-box", null, false, null, null))
			.isInstanceOf(ApiException.class).hasMessageContaining("Unknown folder");
		assertThatThrownBy(() -> f.service.apply("inbox", "1", "explode"))
			.isInstanceOf(ApiException.class).hasMessageContaining("Unknown action");
		verify(f.mailbox, never()).attachment(anyFolder(), anyLong(), anyInt(), anyLong());
	}

	@Test
	void aBrandFilterBecomesAGmailLabelSearch() {
		Fixture f = new Fixture();
		f.service.list("inbox", "invoice", true, "Easy IPTV", 500);
		// The Inbox asks for 50 more, to make up for our own answers it leaves out.
		verify(f.mailbox).list(FolderKey.INBOX, "label:Easy-IPTV is:unread invoice", InboxService.MAX_LIMIT + 50);
		f.service.list("sent", null, false, null, 30);
		verify(f.mailbox).list(FolderKey.SENT, "", 30);
	}

	@Test
	void theInboxLeavesOutOurAnswersButKeepsWebsiteFormEmails() {
		Fixture f = new Fixture();
		Snapshot form = new Snapshot(21, FolderKey.INBOX, "EasyIPTV Contact", "support@easyiptv.ca",
			List.of(new Addr("", "support@easyiptv.ca")), List.of(), List.of(new Addr("", "jane@example.com")), List.of(),
			"[EasyIPTV] New message", Instant.parse("2026-10-05T13:24:00Z"), true, false, List.of("EasyIPTV"), true,
			new MailContent.Parsed("Hi", List.of()), "<f@easyiptv.ca>", null, null);
		Snapshot ourReply = new Snapshot(22, FolderKey.INBOX, "EasyIPTV Support", "support@easyiptv.ca",
			List.of(new Addr("", "jane@example.com")), List.of(), List.of(), List.of(),
			"Re: [EasyIPTV] New message", Instant.parse("2026-10-05T13:30:00Z"), true, false, List.of("EasyIPTV"), true,
			new MailContent.Parsed("Thanks", List.of()), "<r@easyiptv.ca>", null, null);
		Snapshot client = new Snapshot(23, FolderKey.INBOX, "Jane", "jane@example.com",
			List.of(new Addr("", "support@easyiptv.ca")), List.of(), List.of(), List.of(),
			"Re: Re: [EasyIPTV] New message", Instant.parse("2026-10-05T13:40:00Z"), false, false, List.of("EasyIPTV"), false,
			new MailContent.Parsed("Great", List.of()), "<c@example.com>", null, null);
		when(f.mailbox.list(eq(FolderKey.INBOX), eq(""), anyInt())).thenReturn(List.of(client, ourReply, form));

		assertThat(f.service.list("inbox", null, false, null, null)).extracting(InboxDtos.MailSummary::id)
			.containsExactly("23", "21");
	}

	@Test
	void quotesBecomeAQuoteBlockInTheHtmlVersionAndStayOutOfThePreview() throws Exception {
		String body = "Thanks!\n\nOn Mon 5 Oct 2026 at 13:24 UTC, Jane <jane@example.com> wrote:\n> Hello <team>\n> > older\n";

		assertThat(MailComposer.toHtml(body))
			.contains("Thanks!<br>")
			.contains("<blockquote")
			.contains("Hello &lt;team&gt;<br><blockquote")
			.doesNotContain("&gt; Hello");
		assertThat(new MailContent.Parsed(body, List.of()).snippet()).isEqualTo("Thanks!");
		assertThat(new MailContent.Parsed("Merci\n\nLe lun. 5 oct. 2026 à 13:22, X <x@y.ca> a écrit :\n> Bonjour", List.of()).snippet())
			.isEqualTo("Merci");

		MimeMessage built = MailComposer.build(SESSION, new Outgoing(new InternetAddress("support@easyiptv.ca"),
			List.of(new InternetAddress("jane@example.com")), List.of(), "Re: Hi", body, null, null, List.of(), null));
		// The plain text is still what One Base shows; the HTML version is for the client's mail app.
		assertThat(MailContent.parse(built).text()).startsWith("Thanks!").contains("> Hello <team>");
		assertThat(((MimeMultipart) built.getContent()).getContentType()).startsWith("multipart/alternative");
	}

	@Test
	void anEmailFromABrandUsesTheBrandDesign() {
		String html = MailComposer.branded(MailComposer.toHtml("Hello <Jane>"),
			new MailComposer.Branding("Easy IPTV", "easyiptv.ca", "https://app.test/api/brands/7/logo?v=1"));
		assertThat(html)
			.contains("<img src=\"https://app.test/api/brands/7/logo?v=1\"")
			.contains("Hello &lt;Jane&gt;")
			.contains("Easy IPTV &middot; <a href=\"https://easyiptv.ca\"");
		// No logo yet: the brand name in a black badge instead.
		assertThat(MailComposer.branded("x", new MailComposer.Branding("IPTV NOW", "iptvnow.ca", null)))
			.contains(">IPTV NOW</td>").doesNotContain("<img");
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	private static final class Fixture {
		final GmailMailbox mailbox = mock(GmailMailbox.class);
		final BrandRepository brands = mock(BrandRepository.class);
		final InboxService service;

		Fixture() {
			Brand easy = mock(Brand.class);
			when(easy.getId()).thenReturn(7L);
			when(easy.getName()).thenReturn("Easy IPTV");
			when(easy.getDomain()).thenReturn("easyiptv.ca");
			when(easy.getLogo()).thenReturn(new byte[] {1});
			when(easy.getLogoUpdatedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
			when(brands.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(easy));
			when(mailbox.userLabels()).thenReturn(List.of("EasyIPTV", "Personal"));
			InboxSettings settings = new InboxSettings("jamie.responde@gmail.com", "abcd efgh", "Jamie Responder",
				"imap.gmail.com", 993, "smtp.gmail.com", 465, "support");
			service = new InboxService(mailbox, settings, brands, "https://app.test/");
		}
	}

	private static FolderKey anyFolder() {
		return org.mockito.ArgumentMatchers.any(FolderKey.class);
	}

	private static ComposeRequest compose(String from, List<String> to) {
		return new ComposeRequest(from, to, List.of(), "Hello", "Body", null, null, null, null, null, List.of());
	}

	private static Snapshot snapshot(String from, List<Addr> to, String text, String subject, List<String> labels) {
		return new Snapshot(1, FolderKey.INBOX, "EasyIPTV Contact", from, to, List.of(), List.of(), List.of(), subject,
			Instant.parse("2026-10-05T13:24:00Z"), true, false, labels, false, new MailContent.Parsed(text, List.of()),
			"<b@easyiptv.ca>", "<a@example.com>", "<a@example.com>");
	}

	private static MimeBodyPart part(String content, String type, String name) throws Exception {
		MimeBodyPart part = new MimeBodyPart();
		part.setContent(content, type);
		if (name != null) part.setFileName(name);
		return part;
	}

	private static MimeBodyPart file(String name, String type, String bytes) throws Exception {
		MimeBodyPart part = new MimeBodyPart();
		part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes.getBytes(StandardCharsets.UTF_8), type)));
		part.setFileName(name);
		part.setDisposition(MimeBodyPart.ATTACHMENT);
		return part;
	}
}
