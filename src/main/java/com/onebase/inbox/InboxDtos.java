package com.onebase.inbox;

import java.time.Instant;
import java.util.List;

/**
 * What the Inbox API sends and receives.
 *
 * <p>⚠️ Message ids are strings: Gmail's ids are 64-bit numbers, larger than a
 * JavaScript number can hold exactly.
 */
public final class InboxDtos {

	private InboxDtos() {
	}

	public record AccountView(String email, String name) {
	}

	/** {@code group}: "working" (inbox, drafts, sent) or "aside" (archive, spam, trash). */
	public record FolderView(String id, String label, String icon, int unread, String group) {
	}

	/**
	 * A Gmail label used as a brand. {@code logoUrl} and {@code sender} are set when
	 * the label matches a brand in Configuration → Brands.
	 */
	public record BrandView(String name, String logoUrl, String sender) {
	}

	public record SenderView(String name, String email) {
	}

	public record Overview(AccountView account, List<FolderView> folders, List<BrandView> brands, List<SenderView> senders) {
	}

	public record AddressView(String name, String email) {
	}

	public record MailSummary(
			String id,
			String folder,
			String fromName,
			String fromEmail,
			/** The first recipient (name, else address): what Sent and Drafts show instead of the sender. */
			String toLabel,
			String subject,
			Instant receivedAt,
			String snippet,
			boolean read,
			boolean starred,
			List<String> labels,
			String brand,
			int attachmentCount) {
	}

	public record AttachmentView(int index, String name, String contentType, long size) {
	}

	/**
	 * One email, open. {@code replyRecipients} is who Reply answers, {@code replyAllCc}
	 * who Reply all adds, and {@code defaultFrom} the address the answer goes out from.
	 */
	public record MailDetail(
			String id,
			String folder,
			String fromName,
			String fromEmail,
			String subject,
			Instant receivedAt,
			boolean read,
			boolean starred,
			List<String> labels,
			String brand,
			List<AddressView> to,
			List<AddressView> cc,
			List<AddressView> replyTo,
			String body,
			List<AttachmentView> attachments,
			List<String> replyRecipients,
			List<String> replyAllCc,
			String defaultFrom) {
	}

	public record ActionRequest(String folder, String action) {
	}

	/**
	 * A new email, a reply, a forward or a draft.
	 *
	 * <p>{@code replyToId}/{@code replyToFolder}: the email being answered (quoted and
	 * threaded). {@code forwardId}/{@code forwardFolder}: the email being forwarded.
	 * {@code draftId}: the draft this replaces (sent → the draft is removed).
	 * {@code keepAttachments}: file numbers to carry over — from the draft when there is
	 * one ({@code draftId}), otherwise from the forwarded email.
	 */
	public record ComposeRequest(
			String from,
			List<String> to,
			List<String> cc,
			String subject,
			String body,
			String replyToId,
			String replyToFolder,
			String forwardId,
			String forwardFolder,
			String draftId,
			List<Integer> keepAttachments) {
	}

	public record DraftSaved(String id) {
	}
}
