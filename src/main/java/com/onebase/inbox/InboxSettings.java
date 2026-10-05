package com.onebase.inbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one mailbox the Inbox reads: today Jamie's Gmail, which receives every
 * brand address through Cloudflare Email Routing.
 *
 * <p>Set on the server in {@code backend.env} ({@code INBOX_USERNAME},
 * {@code INBOX_PASSWORD} = a Google app password). Without a username the Inbox
 * answers 503 "not connected" instead of failing at startup, so the rest of the
 * app runs locally and in tests without a mailbox.
 *
 * <p>Brand replies go out as {@code <senderLocalPart>@<brand domain>} (support@…),
 * which must exist in the mailbox's Gmail "Send mail as" list — Gmail replaces an
 * unknown From with the account's own address.
 */
@Component
public class InboxSettings {

	private final String username;
	private final String password;
	private final String displayName;
	private final String imapHost;
	private final int imapPort;
	private final String smtpHost;
	private final int smtpPort;
	private final String senderLocalPart;

	public InboxSettings(
			@Value("${onebase.inbox.username:}") String username,
			@Value("${onebase.inbox.password:}") String password,
			@Value("${onebase.inbox.display-name:}") String displayName,
			@Value("${onebase.inbox.imap-host:imap.gmail.com}") String imapHost,
			@Value("${onebase.inbox.imap-port:993}") int imapPort,
			@Value("${onebase.inbox.smtp-host:smtp.gmail.com}") String smtpHost,
			@Value("${onebase.inbox.smtp-port:465}") int smtpPort,
			@Value("${onebase.inbox.sender-local-part:support}") String senderLocalPart) {
		this.username = username.trim();
		// App passwords are shown with spaces ("abcd efgh …"); Google accepts them without.
		this.password = password.replace(" ", "");
		this.displayName = displayName.isBlank() ? this.username : displayName.trim();
		this.imapHost = imapHost;
		this.imapPort = imapPort;
		this.smtpHost = smtpHost;
		this.smtpPort = smtpPort;
		this.senderLocalPart = senderLocalPart.trim();
	}

	public boolean connected() {
		return !username.isEmpty() && !password.isEmpty();
	}

	public String username() { return username; }
	public String password() { return password; }
	public String displayName() { return displayName; }
	public String imapHost() { return imapHost; }
	public int imapPort() { return imapPort; }
	public String smtpHost() { return smtpHost; }
	public int smtpPort() { return smtpPort; }
	public String senderLocalPart() { return senderLocalPart; }
}
