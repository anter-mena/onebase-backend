package com.onebase.inbox;

import com.onebase.inbox.InboxDtos.ActionRequest;
import com.onebase.inbox.InboxDtos.ComposeRequest;
import com.onebase.inbox.InboxDtos.DraftSaved;
import com.onebase.inbox.InboxDtos.MailDetail;
import com.onebase.inbox.InboxDtos.MailSummary;
import com.onebase.inbox.InboxDtos.Overview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The email Inbox. Admins and Commercials ({@code SecurityConfig.COMMERCIAL_API}). */
@RestController
@RequestMapping("/api/inbox")
@Tag(name = "Inbox", description = "The Gmail mailbox, live: read, organise, reply, forward, drafts. Admins and Commercials.")
public class InboxController {

	private final InboxService service;

	public InboxController(InboxService service) {
		this.service = service;
	}

	@GetMapping("/overview")
	@Operation(summary = "The mailbox, its folders with counts, the brands (Gmail labels) and the From addresses")
	public Overview overview() {
		return service.overview();
	}

	@GetMapping("/messages")
	@Operation(summary = "The newest emails of a folder",
		description = "folder: inbox, drafts, sent, archive, junk, trash. q: Gmail search words. brand: a label. limit: up to 200.")
	public List<MailSummary> list(@RequestParam(required = false) String folder,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "false") boolean unread,
			@RequestParam(required = false) String brand,
			@RequestParam(required = false) Integer limit) {
		return service.list(folder, q, unread, brand, limit);
	}

	@GetMapping("/messages/{id}")
	@Operation(summary = "One email, as plain text with its files", description = "markRead=true marks it read in Gmail too.")
	public MailDetail detail(@PathVariable String id, @RequestParam(required = false) String folder,
			@RequestParam(defaultValue = "false") boolean markRead) {
		return service.detail(folder, id, markRead);
	}

	@PostMapping("/messages/{id}/actions")
	@Operation(summary = "read, unread, star, unstar, archive, junk, trash, inbox",
		description = "trash from Trash or Drafts deletes for good. inbox moves it back to the Inbox.")
	public ResponseEntity<Void> act(@PathVariable String id, @RequestBody ActionRequest request) {
		service.apply(request.folder(), id, request.action());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/messages/{id}/attachments/{index}")
	@Operation(summary = "Download one attached file")
	public ResponseEntity<byte[]> attachment(@PathVariable String id, @PathVariable int index,
			@RequestParam(required = false) String folder) {
		GmailMailbox.FileData file = service.attachment(folder, id, index);
		// Always a download, never shown inline: a file from a stranger must not run in our page.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(file.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.body(file.bytes());
	}

	@PostMapping(path = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Send a new email, a reply or a forward",
		description = "Part \"message\" (JSON) + optional parts \"files\". From must be one of the overview's senders.")
	public ResponseEntity<Void> send(@RequestPart("message") ComposeRequest message,
			@RequestPart(name = "files", required = false) List<MultipartFile> files) {
		service.send(message, files);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Save a draft in Gmail", description = "With draftId, it replaces that draft. Returns the new draft id.")
	public DraftSaved saveDraft(@RequestPart("message") ComposeRequest message,
			@RequestPart(name = "files", required = false) List<MultipartFile> files) {
		return new DraftSaved(service.saveDraft(message, files));
	}
}
