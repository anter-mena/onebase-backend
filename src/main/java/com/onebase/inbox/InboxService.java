package com.onebase.inbox;

import com.onebase.brand.Brand;
import com.onebase.brand.BrandRepository;
import com.onebase.common.ApiException;
import com.onebase.inbox.GmailMailbox.Action;
import com.onebase.inbox.GmailMailbox.Addr;
import com.onebase.inbox.GmailMailbox.FolderKey;
import com.onebase.inbox.GmailMailbox.Snapshot;
import com.onebase.inbox.InboxDtos.AccountView;
import com.onebase.inbox.InboxDtos.AddressView;
import com.onebase.inbox.InboxDtos.AttachmentView;
import com.onebase.inbox.InboxDtos.BrandView;
import com.onebase.inbox.InboxDtos.ComposeRequest;
import com.onebase.inbox.InboxDtos.FolderView;
import com.onebase.inbox.InboxDtos.MailDetail;
import com.onebase.inbox.InboxDtos.MailSummary;
import com.onebase.inbox.InboxDtos.Overview;
import com.onebase.inbox.InboxDtos.SenderView;
import com.onebase.inbox.MailComposer.FileData;
import com.onebase.inbox.MailComposer.Outgoing;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Inbox: Jamie's Gmail, read and answered from One Base (Admins and Commercials).
 *
 * <p><b>Brands come from Gmail labels.</b> A label whose name matches a brand in
 * Configuration → Brands (letters and digits only, any case: "EasyIPTV" = "Easy IPTV",
 * or the domain without ".ca") gets that brand's logo, and answers go out from
 * {@code support@<brand domain>}. A label with no brand still filters, with no logo.
 *
 * <p>Only the addresses in {@link #senders} can be used as From — the mailbox's own
 * and each brand's support@, which must be set up in Gmail "Send mail as".
 */
@Service
public class InboxService {

	private static final Logger log = LoggerFactory.getLogger(InboxService.class);

	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 200;
	/** Gmail refuses emails over 25 MB once encoded; 18 MB of files stays under it. */
	static final long MAX_FILES_BYTES = 18L * 1024 * 1024;
	static final int MAX_RECIPIENTS = 50;

	private final GmailMailbox mailbox;
	private final InboxSettings settings;
	private final BrandRepository brands;
	private final String frontendUrl;
	private volatile JavaMailSenderImpl smtp;

	public InboxService(GmailMailbox mailbox, InboxSettings settings, BrandRepository brands,
			@org.springframework.beans.factory.annotation.Value("${onebase.frontend-url}") String frontendUrl) {
		this.mailbox = mailbox;
		this.settings = settings;
		this.brands = brands;
		// Brand logos are served (publicly) through the frontend's https address, so mail apps load them.
		this.frontendUrl = frontendUrl.replaceAll("/+$", "");
	}

	// ── Reading ───────────────────────────────────────────────────────────────

	public Overview overview() {
		Map<FolderKey, Integer> counts = mailbox.counts();
		List<FolderView> folders = List.of(
			new FolderView("inbox", "Inbox", "inbox", counts.getOrDefault(FolderKey.INBOX, 0), "working"),
			new FolderView("drafts", "Drafts", "file", counts.getOrDefault(FolderKey.DRAFTS, 0), "working"),
			new FolderView("sent", "Sent", "send", 0, "working"),
			new FolderView("archive", "Archive", "archive", 0, "aside"),
			new FolderView("junk", "Spam", "archive-x", counts.getOrDefault(FolderKey.JUNK, 0), "aside"),
			new FolderView("trash", "Trash", "trash", 0, "aside"));
		List<BrandMatch> matches = brandMatches();
		List<BrandView> brandViews = matches.stream()
			.map(m -> new BrandView(m.label(), logoUrl(m.brand()), m.sender() == null ? null : m.sender().email()))
			.toList();
		return new Overview(new AccountView(settings.username(), settings.displayName()), folders, brandViews, senders(matches));
	}

	public List<MailSummary> list(String folder, String q, boolean unread, String brand, Integer limit) {
		FolderKey key = FolderKey.of(folder);
		List<String> parts = new ArrayList<>();
		if (brand != null && !brand.isBlank()) parts.add("label:" + labelQuery(brand));
		if (unread) parts.add("is:unread");
		if (q != null && !q.isBlank()) parts.add(q.trim());
		int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
		List<BrandMatch> matches = brandMatches();
		if (key != FolderKey.INBOX) {
			return mailbox.list(key, String.join(" ", parts), size).stream().map(s -> summary(s, matches)).toList();
		}
		// Gmail puts our reply in the Inbox too, because its conversation is there. An email we
		// sent to someone outside belongs in Sent only; a website form email is sent to our own
		// support@ address, so it stays.
		Set<String> ours = new LinkedHashSet<>();
		senders(matches).forEach(sender -> ours.add(sender.email()));
		return mailbox.list(key, String.join(" ", parts), Math.min(MAX_LIMIT + 50, size + 50)).stream()
			.filter(s -> !s.sentByUs() || concat(s.to(), s.cc()).stream().anyMatch(a -> ours.contains(a.email())))
			.limit(size)
			.map(s -> summary(s, matches))
			.toList();
	}

	public MailDetail detail(String folder, String id, boolean markRead) {
		FolderKey key = FolderKey.of(folder);
		long msgId = parseId(id);
		Snapshot s = mailbox.get(key, msgId);
		boolean read = s.read();
		if (markRead && !read && key != FolderKey.DRAFTS) {
			mailbox.apply(key, msgId, Action.READ);
			read = true;
		}
		List<BrandMatch> matches = brandMatches();
		List<SenderView> senders = senders(matches);
		Set<String> ours = new LinkedHashSet<>();
		senders.forEach(sender -> ours.add(sender.email().toLowerCase(Locale.ROOT)));
		BrandMatch brand = brandOf(s, matches);

		// Reply answers Reply-To (a website form email is "from" our support@ with the client in
		// Reply-To), else the sender; an email we sent ourselves is answered to its recipients.
		List<String> replyRecipients = new ArrayList<>();
		s.replyTo().stream().map(Addr::email).filter(e -> !ours.contains(e)).forEach(replyRecipients::add);
		if (replyRecipients.isEmpty()) {
			if (ours.contains(s.fromEmail())) {
				s.to().stream().map(Addr::email).filter(e -> !ours.contains(e)).forEach(replyRecipients::add);
			} else if (!s.fromEmail().isBlank()) {
				replyRecipients.add(s.fromEmail());
			}
		}
		List<String> replyAllCc = new ArrayList<>();
		for (Addr a : concat(s.to(), s.cc())) {
			if (!ours.contains(a.email()) && !replyRecipients.contains(a.email()) && !replyAllCc.contains(a.email())
					&& !isOwnDomainAlias(a.email(), matches)) {
				replyAllCc.add(a.email());
			}
		}

		return new MailDetail(
			String.valueOf(s.id()), key.id(), s.fromName(), s.fromEmail(), s.subject(), s.date(), read, s.starred(),
			s.labels(), brand == null ? null : brand.label(),
			views(s.to()), views(s.cc()), views(s.replyTo()),
			s.content().text(),
			s.content().attachments().stream()
				.map(a -> new AttachmentView(a.index(), a.name(), a.contentType(), a.size())).toList(),
			replyRecipients, replyAllCc, defaultFrom(s, brand, senders));
	}

	public GmailMailbox.FileData attachment(String folder, String id, int index) {
		return mailbox.attachment(FolderKey.of(folder), parseId(id), index, MAX_FILES_BYTES + 1);
	}

	public void apply(String folder, String id, String action) {
		Action parsed;
		try {
			parsed = Action.valueOf(action == null ? "" : action.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("Unknown action: " + action);
		}
		mailbox.apply(FolderKey.of(folder), parseId(id), parsed);
	}

	// ── Writing ───────────────────────────────────────────────────────────────

	public void send(ComposeRequest request, List<MultipartFile> uploads) {
		Prepared prepared = prepare(request, uploads, true);
		try {
			MimeMessage message = MailComposer.build(smtp().getSession(), prepared.outgoing());
			smtp().send(message);
		} catch (MailAuthenticationException e) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"Gmail refused the mailbox password. Check the app password on the server.");
		} catch (MessagingException | MailException e) {
			log.warn("Inbox send failed", e);
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Gmail did not accept the email. Please try again.");
		}
		// Gmail files the sent copy in Sent itself. The draft it came from is no longer needed.
		if (prepared.draftId() != null) {
			try {
				mailbox.deleteDraft(prepared.draftId());
			} catch (ApiException e) {
				log.warn("Sent, but the draft {} could not be removed: {}", prepared.draftId(), e.getMessage());
			}
		}
	}

	public String saveDraft(ComposeRequest request, List<MultipartFile> uploads) {
		Prepared prepared = prepare(request, uploads, false);
		try {
			MimeMessage draft = MailComposer.build(Session.getInstance(new Properties()), prepared.outgoing());
			return String.valueOf(mailbox.saveDraft(draft, prepared.draftId()));
		} catch (MessagingException e) {
			throw ApiException.badRequest("The draft could not be built: " + e.getMessage());
		}
	}

	private record Prepared(Outgoing outgoing, Long draftId) {
	}

	private Prepared prepare(ComposeRequest request, List<MultipartFile> uploads, boolean sending) {
		if (request == null) throw ApiException.badRequest("The email is empty.");
		List<BrandMatch> matches = brandMatches();
		List<SenderView> senders = senders(matches);
		SenderView from = senders.stream()
			.filter(s -> s.email().equalsIgnoreCase(request.from() == null ? "" : request.from().trim()))
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("Choose one of the addresses in the From list."));

		List<InternetAddress> to = addresses(request.to(), "To");
		List<InternetAddress> cc = addresses(request.cc(), "Cc");
		if (sending && to.isEmpty()) throw ApiException.badRequest("Add at least one recipient.");
		if (to.size() + cc.size() > MAX_RECIPIENTS) throw ApiException.badRequest("At most " + MAX_RECIPIENTS + " recipients.");

		String subject = request.subject() == null ? "" : request.subject().replaceAll("[\\r\\n]+", " ").trim();
		if (subject.length() > 500) throw ApiException.badRequest("The subject is too long (500 characters at most).");
		String body = request.body() == null ? "" : request.body();
		if (body.length() > 200_000) throw ApiException.badRequest("The message is too long.");
		if (sending && subject.isEmpty() && body.isBlank()) throw ApiException.badRequest("Write a subject or a message.");

		Long draftId = request.draftId() == null || request.draftId().isBlank() ? null : parseId(request.draftId());
		String inReplyTo = null;
		String references = null;

		Snapshot replyTo = null;
		if (request.replyToId() != null && !request.replyToId().isBlank()) {
			replyTo = mailbox.get(FolderKey.of(request.replyToFolder()), parseId(request.replyToId()));
			inReplyTo = replyTo.messageIdHeader();
			references = MailComposer.references(replyTo);
			body = MailComposer.withQuote(body, replyTo);
		}
		Snapshot forward = null;
		if (request.forwardId() != null && !request.forwardId().isBlank()) {
			forward = mailbox.get(FolderKey.of(request.forwardFolder()), parseId(request.forwardId()));
			body = MailComposer.withForward(body, forward);
		}
		Snapshot draft = draftId == null ? null : mailbox.get(FolderKey.DRAFTS, draftId);
		if (replyTo == null && draft != null) {
			// Sending a saved reply: it keeps its place in the conversation.
			inReplyTo = draft.inReplyTo();
			references = draft.references();
		}

		List<FileData> files = new ArrayList<>();
		long total = 0;
		List<Integer> keep = request.keepAttachments() == null ? List.of() : request.keepAttachments();
		if (!keep.isEmpty()) {
			// Once a draft exists it holds the files (a saved forward included); before that, the forwarded email.
			FolderKey sourceFolder = draftId != null ? FolderKey.DRAFTS : FolderKey.of(request.forwardFolder());
			Long sourceId = draftId != null ? draftId : forward != null ? Long.valueOf(forward.id()) : null;
			if (sourceId == null) throw ApiException.badRequest("There is no email to take the files from.");
			for (Integer index : new LinkedHashSet<>(keep)) {
				if (index == null || index < 0) continue;
				GmailMailbox.FileData data = mailbox.attachment(sourceFolder, sourceId, index, MAX_FILES_BYTES + 1);
				total += data.bytes().length;
				files.add(new FileData(data.name(), data.contentType(), data.bytes()));
			}
		}
		for (MultipartFile upload : uploads == null ? List.<MultipartFile>of() : uploads) {
			if (upload == null || upload.isEmpty()) continue;
			total += upload.getSize();
			if (total > MAX_FILES_BYTES) break;
			try {
				String name = upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename().replaceAll("[\\r\\n\\\\/]", "_");
				files.add(new FileData(name, upload.getContentType(), upload.getBytes()));
			} catch (IOException e) {
				throw ApiException.badRequest("A file could not be read. Please attach it again.");
			}
		}
		if (total > MAX_FILES_BYTES) throw ApiException.badRequest("The files are too big together (18 MB at most).");

		InternetAddress fromAddress;
		try {
			fromAddress = new InternetAddress(from.email(), from.name(), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw ApiException.badRequest("The From address is not valid.");
		}
		return new Prepared(new Outgoing(fromAddress, to, cc, subject, body, inReplyTo, references, files,
			branding(from, matches)), draftId);
	}

	// ── Brands and senders ─────────────────────────────────────────────────────

	/** A Gmail label, the brand it matches (or none), and the address answers go out from. */
	private record BrandMatch(String label, Brand brand, SenderView sender) {
	}

	private List<BrandMatch> brandMatches() {
		List<Brand> all = brands.findAllByOrderByCreatedAtAscIdAsc();
		List<BrandMatch> result = new ArrayList<>();
		for (String label : mailbox.userLabels()) {
			Brand match = null;
			String key = normalize(label);
			for (Brand brand : all) {
				String domain = brand.getDomain() == null ? "" : brand.getDomain();
				String domainName = domain.contains(".") ? domain.substring(0, domain.lastIndexOf('.')) : domain;
				if (key.equals(normalize(brand.getName())) || key.equals(normalize(domainName)) || key.equals(normalize(domain))) {
					match = brand;
					break;
				}
			}
			SenderView sender = match == null || match.getDomain() == null || match.getDomain().isBlank() ? null
				: new SenderView(label + " Support", settings.senderLocalPart() + "@" + match.getDomain().toLowerCase(Locale.ROOT));
			result.add(new BrandMatch(label, match, sender));
		}
		return result;
	}

	/** The design of an email from this address: its brand, or the mailbox's own name. */
	private MailComposer.Branding branding(SenderView from, List<BrandMatch> matches) {
		for (BrandMatch m : matches) {
			if (m.sender() != null && m.brand() != null && m.sender().email().equalsIgnoreCase(from.email())) {
				String logo = logoUrl(m.brand());
				return new MailComposer.Branding(m.brand().getName(), m.brand().getDomain(), logo == null ? null : frontendUrl + logo);
			}
		}
		return new MailComposer.Branding(settings.displayName(), null, null);
	}

	private List<SenderView> senders(List<BrandMatch> matches) {
		List<SenderView> senders = new ArrayList<>();
		senders.add(new SenderView(settings.displayName(), settings.username().toLowerCase(Locale.ROOT)));
		for (BrandMatch m : matches) {
			if (m.sender() != null && senders.stream().noneMatch(s -> s.email().equals(m.sender().email()))) {
				senders.add(m.sender());
			}
		}
		return senders;
	}

	private static BrandMatch brandOf(Snapshot s, List<BrandMatch> matches) {
		for (String label : s.labels()) {
			for (BrandMatch m : matches) {
				if (m.label().equalsIgnoreCase(label)) return m;
			}
		}
		// No label yet (the filter runs on arrival): fall back to the address it was sent to.
		for (Addr a : concat(s.to(), s.cc())) {
			for (BrandMatch m : matches) {
				if (m.brand() != null && m.brand().getDomain() != null
						&& a.email().endsWith("@" + m.brand().getDomain().toLowerCase(Locale.ROOT))) {
					return m;
				}
			}
		}
		return null;
	}

	/**
	 * The From of an answer: the exact address the email was sent to when we can send
	 * as it, else the brand's support@, else the mailbox itself.
	 */
	private static String defaultFrom(Snapshot s, BrandMatch brand, List<SenderView> senders) {
		for (Addr a : concat(s.to(), s.cc())) {
			for (SenderView sender : senders) {
				if (sender.email().equalsIgnoreCase(a.email()) && senders.indexOf(sender) > 0) return sender.email();
			}
		}
		if (brand != null && brand.sender() != null) return brand.sender().email();
		if (senders.stream().anyMatch(sender -> sender.email().equalsIgnoreCase(s.fromEmail()))) return s.fromEmail();
		return senders.get(0).email();
	}

	private static boolean isOwnDomainAlias(String email, List<BrandMatch> matches) {
		for (BrandMatch m : matches) {
			if (m.brand() != null && m.brand().getDomain() != null
					&& email.endsWith("@" + m.brand().getDomain().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private MailSummary summary(Snapshot s, List<BrandMatch> matches) {
		BrandMatch brand = brandOf(s, matches);
		String toLabel = s.to().isEmpty() ? "" : s.to().get(0).name().isBlank() ? s.to().get(0).email() : s.to().get(0).name();
		if (s.to().size() > 1) toLabel += " +" + (s.to().size() - 1);
		return new MailSummary(String.valueOf(s.id()), s.folder().id(), s.fromName(), s.fromEmail(), toLabel, s.subject(), s.date(),
			s.content().snippet(), s.read(), s.starred(), s.labels(), brand == null ? null : brand.label(),
			s.content().attachments().size());
	}

	private static String logoUrl(Brand brand) {
		if (brand == null || brand.getLogo() == null || brand.getLogoUpdatedAt() == null) return null;
		return "/api/brands/" + brand.getId() + "/logo?v=" + brand.getLogoUpdatedAt().toEpochMilli();
	}

	/** Gmail's label search: spaces become dashes. */
	private static String labelQuery(String label) {
		return label.trim().replaceAll("\\s+", "-").replace("\"", "");
	}

	static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	// ── Small helpers ──────────────────────────────────────────────────────────

	private JavaMailSenderImpl smtp() {
		JavaMailSenderImpl current = smtp;
		if (current != null) return current;
		synchronized (this) {
			if (smtp == null) {
				JavaMailSenderImpl sender = new JavaMailSenderImpl();
				sender.setHost(settings.smtpHost());
				sender.setPort(settings.smtpPort());
				sender.setUsername(settings.username());
				sender.setPassword(settings.password());
				sender.setDefaultEncoding("UTF-8");
				Properties props = sender.getJavaMailProperties();
				props.put("mail.smtp.auth", "true");
				props.put("mail.smtp.ssl.enable", String.valueOf(settings.smtpPort() == 465));
				props.put("mail.smtp.starttls.enable", String.valueOf(settings.smtpPort() != 465));
				props.put("mail.smtp.ssl.checkserveridentity", "true");
				props.put("mail.smtp.connectiontimeout", "10000");
				props.put("mail.smtp.timeout", "30000");
				props.put("mail.smtp.writetimeout", "60000");
				smtp = sender;
			}
			return smtp;
		}
	}

	private static List<InternetAddress> addresses(List<String> values, String field) {
		List<InternetAddress> result = new ArrayList<>();
		if (values == null) return result;
		for (String value : values) {
			if (value == null || value.isBlank()) continue;
			try {
				InternetAddress address = new InternetAddress(value.trim(), true);
				address.validate();
				if (address.getAddress() == null || !address.getAddress().contains("@")) throw new AddressException();
				result.add(address);
			} catch (AddressException e) {
				throw ApiException.badRequest(field + ": \"" + value.trim() + "\" is not a valid email address.");
			}
		}
		return result;
	}

	private static long parseId(String id) {
		try {
			return Long.parseUnsignedLong(id == null ? "" : id.trim());
		} catch (NumberFormatException e) {
			throw ApiException.badRequest("Unknown email id.");
		}
	}

	private static List<AddressView> views(List<Addr> addrs) {
		return addrs.stream().map(a -> new AddressView(a.name(), a.email())).toList();
	}

	private static List<Addr> concat(List<Addr> a, List<Addr> b) {
		List<Addr> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}
}
