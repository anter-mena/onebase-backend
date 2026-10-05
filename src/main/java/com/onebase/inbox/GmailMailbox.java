package com.onebase.inbox;

import com.onebase.common.ApiException;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.eclipse.angus.mail.gimap.GmailFolder;
import org.eclipse.angus.mail.gimap.GmailMessage;
import org.eclipse.angus.mail.gimap.GmailMsgIdTerm;
import org.eclipse.angus.mail.gimap.GmailRawSearchTerm;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The live connection to the Gmail mailbox (IMAP with Gmail's extensions).
 *
 * <p><b>Gmail stays the only copy.</b> Nothing is stored in our database: every
 * list, read, star, archive and draft is done in Gmail itself, so the mailbox
 * looks the same in Gmail and in One Base, always.
 *
 * <p>Messages are identified by Gmail's own message id (X-GM-MSGID), which stays
 * the same when a message moves between folders. Every call takes the folder too,
 * because Spam and Trash are not part of "All Mail".
 *
 * <p>One connection, used by one request at a time ({@link #lock}). Enough for a
 * small team; Gmail allows 15 per account if this ever needs a pool.
 */
@Component
public class GmailMailbox {

	private static final Logger log = LoggerFactory.getLogger(GmailMailbox.class);

	/** The folders the Inbox shows, in the order of its navigation. */
	enum FolderKey {
		INBOX, DRAFTS, SENT, ARCHIVE, JUNK, TRASH;

		static FolderKey of(String value) {
			if (value == null || value.isBlank()) return INBOX;
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("Unknown folder: " + value);
			}
		}

		String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	enum Action { READ, UNREAD, STAR, UNSTAR, ARCHIVE, JUNK, TRASH, INBOX }

	record Addr(String name, String email) {
	}

	/** A message read out of Gmail, with nothing left attached to the connection. */
	record Snapshot(
			long id,
			FolderKey folder,
			String fromName,
			String fromEmail,
			List<Addr> to,
			List<Addr> cc,
			List<Addr> replyTo,
			List<Addr> deliveredTo,
			String subject,
			Instant date,
			boolean read,
			boolean starred,
			List<String> labels,
			MailContent.Parsed content,
			String messageIdHeader,
			String inReplyTo,
			String references) {
	}

	record FileData(String name, String contentType, byte[] bytes) {
	}

	/** Bodies never change once sent, so a parsed one is kept (id → text and files). */
	private final Map<Long, MailContent.Parsed> parsedCache = Collections.synchronizedMap(
		new LinkedHashMap<>(256, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, MailContent.Parsed> eldest) {
				return size() > 3_000;
			}
		});

	private final InboxSettings settings;
	private final Object lock = new Object();
	private Store store;
	/** Folder role (\Sent, \Drafts …) → its real name, which Gmail translates ("[Gmail]/Messages envoyés"). */
	private Map<String, String> roles = Map.of();
	private volatile List<String> labelCache;
	private volatile long labelCacheAt;

	public GmailMailbox(InboxSettings settings) {
		this.settings = settings;
	}

	// ── What the screen asks for ─────────────────────────────────────────────

	/** Unread in Inbox and Spam, the number of drafts. */
	Map<FolderKey, Integer> counts() {
		return withStore(store -> {
			Map<FolderKey, Integer> counts = new LinkedHashMap<>();
			counts.put(FolderKey.INBOX, store.getFolder("INBOX").getUnreadMessageCount());
			counts.put(FolderKey.DRAFTS, folder(store, FolderKey.DRAFTS).getMessageCount());
			counts.put(FolderKey.JUNK, folder(store, FolderKey.JUNK).getUnreadMessageCount());
			return counts;
		});
	}

	/** The labels the account created itself (EasyIPTV, IPTVNow …), not Gmail's own folders. Kept a minute. */
	List<String> userLabels() {
		List<String> cached = labelCache;
		if (cached != null && System.nanoTime() - labelCacheAt < 60_000_000_000L) return cached;
		List<String> fresh = withStore(store -> {
			List<String> labels = new ArrayList<>();
			for (Folder f : store.getDefaultFolder().list("*")) {
				String name = f.getFullName();
				if (name.equalsIgnoreCase("INBOX") || name.startsWith("[Gmail]") || name.startsWith("[Google Mail]")) continue;
				if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
				labels.add(name);
			}
			labels.sort(String.CASE_INSENSITIVE_ORDER);
			return List.copyOf(labels);
		});
		labelCache = fresh;
		labelCacheAt = System.nanoTime();
		return fresh;
	}

	/**
	 * The newest messages of a folder, optionally narrowed with Gmail's own search
	 * language ("label:EasyIPTV is:unread invoice").
	 */
	List<Snapshot> list(FolderKey key, String gmailQuery, int limit) {
		return withStore(store -> {
			Folder folder = folder(store, key);
			folder.open(Folder.READ_ONLY);
			try {
				String query = combine(baseQuery(key), gmailQuery);
				Message[] messages;
				if (query.isBlank()) {
					int total = folder.getMessageCount();
					messages = total == 0 ? new Message[0] : folder.getMessages(Math.max(1, total - limit + 1), total);
				} else {
					Message[] found = folder.search(new GmailRawSearchTerm(query));
					Arrays.sort(found, Comparator.comparingInt(Message::getMessageNumber));
					messages = Arrays.copyOfRange(found, Math.max(0, found.length - limit), found.length);
				}
				folder.fetch(messages, profile());
				List<Snapshot> result = new ArrayList<>(messages.length);
				for (Message m : messages) {
					result.add(snapshot((GmailMessage) m, key));
				}
				result.sort(Comparator.comparing(Snapshot::date, Comparator.nullsLast(Comparator.reverseOrder())));
				return result;
			} finally {
				close(folder, false);
			}
		});
	}

	Snapshot get(FolderKey key, long id) {
		return withStore(store -> {
			Folder folder = folder(store, key);
			folder.open(Folder.READ_ONLY);
			try {
				GmailMessage message = find(folder, id);
				folder.fetch(new Message[] {message}, profile());
				return snapshot(message, key);
			} finally {
				close(folder, false);
			}
		});
	}

	FileData attachment(FolderKey key, long id, int index, long maxBytes) {
		return withStore(store -> {
			Folder folder = folder(store, key);
			folder.open(Folder.READ_ONLY);
			try {
				Part part = MailContent.attachmentPart(find(folder, id), index);
				if (part == null) throw ApiException.notFound("This file is not in the email any more.");
				MailContent.Parsed parsed = MailContent.parse(find(folder, id));
				MailContent.Attachment meta = parsed.attachments().get(index);
				return new FileData(meta.name(), meta.contentType(), MailContent.read(part, maxBytes));
			} finally {
				close(folder, false);
			}
		});
	}

	void apply(FolderKey key, long id, Action action) {
		withStore(store -> {
			Folder folder = folder(store, key);
			folder.open(Folder.READ_WRITE);
			boolean expunge = false;
			try {
				Message message = find(folder, id);
				Message[] one = {message};
				IMAPFolder imap = (IMAPFolder) folder;
				switch (action) {
					case READ -> message.setFlag(Flags.Flag.SEEN, true);
					case UNREAD -> message.setFlag(Flags.Flag.SEEN, false);
					case STAR -> message.setFlag(Flags.Flag.FLAGGED, true);
					case UNSTAR -> message.setFlag(Flags.Flag.FLAGGED, false);
					case ARCHIVE -> {
						// Gmail: moving out of INBOX into All Mail removes the Inbox label — that is archiving.
						if (key != FolderKey.INBOX) throw ApiException.badRequest("Only an Inbox email can be archived.");
						imap.moveMessages(one, folder(store, FolderKey.ARCHIVE));
					}
					case JUNK -> {
						if (key == FolderKey.JUNK) throw ApiException.badRequest("This email is already in Spam.");
						imap.moveMessages(one, folder(store, FolderKey.JUNK));
					}
					case TRASH -> {
						if (key == FolderKey.TRASH || key == FolderKey.DRAFTS) {
							// In Trash: delete for good. A draft is simply thrown away.
							message.setFlag(Flags.Flag.DELETED, true);
							expunge = true;
						} else {
							imap.moveMessages(one, folder(store, FolderKey.TRASH));
						}
					}
					case INBOX -> {
						if (key == FolderKey.INBOX) throw ApiException.badRequest("This email is already in the Inbox.");
						if (key == FolderKey.ARCHIVE) {
							// Copying into INBOX adds the Inbox label back; it stays in All Mail as well.
							imap.copyMessages(one, store.getFolder("INBOX"));
						} else {
							imap.moveMessages(one, store.getFolder("INBOX"));
						}
					}
				}
				return null;
			} finally {
				close(folder, expunge);
			}
		});
	}

	/** Saves a draft in Gmail's Drafts and returns its id; {@code replaceId} is the draft it replaces. */
	long saveDraft(MimeMessage draft, Long replaceId) {
		return withStore(store -> {
			IMAPFolder drafts = (IMAPFolder) folder(store, FolderKey.DRAFTS);
			drafts.open(Folder.READ_WRITE);
			boolean expunge = false;
			try {
				draft.setFlag(Flags.Flag.DRAFT, true);
				draft.setFlag(Flags.Flag.SEEN, true);
				AppendUID[] appended = drafts.appendUIDMessages(new Message[] {draft});
				if (appended == null || appended.length == 0 || appended[0] == null) {
					throw new MessagingException("Gmail did not return the new draft");
				}
				GmailMessage saved = (GmailMessage) ((UIDFolder) drafts).getMessageByUID(appended[0].uid);
				long id = saved.getMsgId();
				if (replaceId != null && replaceId != id) {
					Message old = findOrNull(drafts, replaceId);
					if (old != null) {
						old.setFlag(Flags.Flag.DELETED, true);
						expunge = true;
					}
				}
				return id;
			} finally {
				close(drafts, expunge);
			}
		});
	}

	/** Removes a draft after it was sent. Already gone is fine. */
	void deleteDraft(long id) {
		withStore(store -> {
			Folder drafts = folder(store, FolderKey.DRAFTS);
			drafts.open(Folder.READ_WRITE);
			boolean expunge = false;
			try {
				Message old = findOrNull(drafts, id);
				if (old != null) {
					old.setFlag(Flags.Flag.DELETED, true);
					expunge = true;
				}
				return null;
			} finally {
				close(drafts, expunge);
			}
		});
	}

	// ── Plumbing ──────────────────────────────────────────────────────────────

	@FunctionalInterface
	private interface Work<T> {
		T run(Store store) throws MessagingException, IOException;
	}

	private <T> T withStore(Work<T> work) {
		if (!settings.connected()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The mailbox is not connected yet.");
		}
		synchronized (lock) {
			for (int attempt = 1; ; attempt++) {
				try {
					return work.run(connectedStore());
				} catch (AuthenticationFailedException e) {
					disconnect();
					log.warn("Gmail refused the Inbox sign-in for {}", settings.username());
					throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
						"Gmail refused the mailbox password. Check the app password on the server.");
				} catch (StoreClosedException | FolderClosedException e) {
					disconnect();
					if (attempt >= 2) throw unreachable(e);
				} catch (MessagingException | IOException e) {
					disconnect();
					if (attempt >= 2) throw unreachable(e);
				}
			}
		}
	}

	private ApiException unreachable(Exception e) {
		log.warn("Gmail IMAP call failed", e);
		return new ApiException(HttpStatus.BAD_GATEWAY, "Gmail did not answer. Please try again in a moment.");
	}

	private Store connectedStore() throws MessagingException {
		if (store != null && store.isConnected()) return store;
		Properties props = new Properties();
		props.put("mail.store.protocol", "gimaps");
		props.put("mail.gimaps.host", settings.imapHost());
		props.put("mail.gimaps.port", String.valueOf(settings.imapPort()));
		props.put("mail.gimaps.connectiontimeout", "10000");
		props.put("mail.gimaps.timeout", "30000");
		props.put("mail.gimaps.writetimeout", "30000");
		props.put("mail.gimaps.ssl.checkserveridentity", "true");
		Session session = Session.getInstance(props);
		Store fresh = session.getStore("gimaps");
		fresh.connect(settings.imapHost(), settings.imapPort(), settings.username(), settings.password());
		store = fresh;
		roles = readRoles(fresh);
		return fresh;
	}

	private void disconnect() {
		if (store == null) return;
		try {
			store.close();
		} catch (MessagingException ignored) {
			// already gone
		}
		store = null;
	}

	private static Map<String, String> readRoles(Store store) throws MessagingException {
		Map<String, String> found = new LinkedHashMap<>();
		for (Folder f : store.getDefaultFolder().list("*")) {
			if (!(f instanceof IMAPFolder imap)) continue;
			for (String attribute : imap.getAttributes()) {
				switch (attribute) {
					case "\\Sent", "\\Drafts", "\\Junk", "\\Trash", "\\All" -> found.putIfAbsent(attribute, f.getFullName());
					default -> {
					}
				}
			}
		}
		return found;
	}

	private Folder folder(Store store, FolderKey key) throws MessagingException {
		String role = switch (key) {
			case INBOX -> null;
			case DRAFTS -> "\\Drafts";
			case SENT -> "\\Sent";
			case ARCHIVE -> "\\All";
			case JUNK -> "\\Junk";
			case TRASH -> "\\Trash";
		};
		if (role == null) return store.getFolder("INBOX");
		String name = roles.get(role);
		if (name == null) throw new MessagingException("The mailbox has no " + role + " folder");
		return store.getFolder(name);
	}

	/** Archive = All Mail minus what is still in the Inbox, sent, or a draft. */
	private static String baseQuery(FolderKey key) {
		return key == FolderKey.ARCHIVE ? "-in:inbox -in:sent -in:drafts" : "";
	}

	private static String combine(String a, String b) {
		String left = a == null ? "" : a.trim();
		String right = b == null ? "" : b.trim();
		if (left.isEmpty()) return right;
		if (right.isEmpty()) return left;
		return left + " " + right;
	}

	private static FetchProfile profile() {
		FetchProfile profile = new FetchProfile();
		profile.add(FetchProfile.Item.ENVELOPE);
		profile.add(FetchProfile.Item.FLAGS);
		profile.add(FetchProfile.Item.CONTENT_INFO);
		profile.add(GmailFolder.FetchProfileItem.MSGID);
		profile.add(GmailFolder.FetchProfileItem.LABELS);
		profile.add("In-Reply-To");
		profile.add("References");
		profile.add("Delivered-To");
		profile.add("X-Original-To");
		return profile;
	}

	private static GmailMessage find(Folder folder, long id) throws MessagingException {
		GmailMessage message = findOrNull(folder, id);
		if (message == null) throw ApiException.notFound("This email is not in this folder any more.");
		return message;
	}

	private static GmailMessage findOrNull(Folder folder, long id) throws MessagingException {
		Message[] found = folder.search(new GmailMsgIdTerm(id));
		return found.length == 0 ? null : (GmailMessage) found[0];
	}

	private Snapshot snapshot(GmailMessage m, FolderKey key) throws MessagingException, IOException {
		long id = m.getMsgId();
		MailContent.Parsed content = parsedCache.get(id);
		if (content == null) {
			content = MailContent.parse(m);
			parsedCache.put(id, content);
		}
		Addr from = first(m.getFrom());
		java.util.Date sent = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
		return new Snapshot(
			id,
			key,
			from == null ? "" : from.name(),
			from == null ? "" : from.email(),
			addrs(m.getRecipients(Message.RecipientType.TO)),
			addrs(m.getRecipients(Message.RecipientType.CC)),
			addrs(m.getReplyTo()),
			headerAddrs(m, "Delivered-To", "X-Original-To"),
			m.getSubject() == null ? "" : m.getSubject(),
			sent == null ? null : sent.toInstant(),
			m.isSet(Flags.Flag.SEEN),
			m.isSet(Flags.Flag.FLAGGED),
			userLabels(m.getLabels()),
			content,
			m.getMessageID(),
			firstHeader(m, "In-Reply-To"),
			firstHeader(m, "References"));
	}

	private static List<String> userLabels(String[] labels) {
		if (labels == null) return List.of();
		List<String> result = new ArrayList<>();
		for (String label : labels) {
			if (label == null || label.isBlank() || label.startsWith("\\")) continue;
			result.add(label);
		}
		return result;
	}

	private static String firstHeader(Message m, String name) throws MessagingException {
		String[] values = m.getHeader(name);
		return values == null || values.length == 0 ? null : values[0];
	}

	private static List<Addr> headerAddrs(Message m, String... names) throws MessagingException {
		List<Addr> result = new ArrayList<>();
		for (String name : names) {
			String[] values = m.getHeader(name);
			if (values == null) continue;
			for (String value : values) {
				try {
					result.addAll(addrs(InternetAddress.parseHeader(value, false)));
				} catch (MessagingException ignored) {
					// a broken header is not worth failing the message for
				}
			}
		}
		return result;
	}

	private static Addr first(Address[] addresses) {
		List<Addr> list = addrs(addresses);
		return list.isEmpty() ? null : list.get(0);
	}

	private static List<Addr> addrs(Address[] addresses) {
		if (addresses == null) return List.of();
		List<Addr> result = new ArrayList<>();
		for (Address address : addresses) {
			if (address instanceof InternetAddress ia && ia.getAddress() != null) {
				result.add(new Addr(ia.getPersonal() == null ? "" : ia.getPersonal(), ia.getAddress().toLowerCase(Locale.ROOT)));
			}
		}
		return result;
	}

	private static void close(Folder folder, boolean expunge) {
		try {
			if (folder.isOpen()) folder.close(expunge);
		} catch (MessagingException ignored) {
			// the connection is reset on the next call if it matters
		}
	}
}
