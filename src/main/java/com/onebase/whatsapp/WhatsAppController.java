package com.onebase.whatsapp;

import com.onebase.security.AuthPrincipal;
import com.onebase.whatsapp.WhatsAppService.ConversationView;
import com.onebase.whatsapp.WhatsAppService.MessageView;
import com.onebase.whatsapp.WhatsAppService.StatusView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The WhatsApp Inbox. Admins and Commercials ({@code SecurityConfig.COMMERCIAL_API}). */
@RestController
@RequestMapping("/api/whatsapp")
@Tag(name = "WhatsApp", description = "Conversations from the WhatsApp number: read, answer (text, files, template). Admins and Commercials.")
public class WhatsAppController {

	public record TextRequest(String text) {
	}

	private final WhatsAppService service;

	public WhatsAppController(WhatsAppService service) {
		this.service = service;
	}

	@GetMapping("/status")
	@Operation(summary = "Whether WhatsApp is connected, and how many messages are unread")
	public StatusView status() {
		return service.status();
	}

	@GetMapping("/conversations")
	@Operation(summary = "Every conversation, newest first", description = "q: name, number or last words. filter: all, read, unread.")
	public List<ConversationView> conversations(@RequestParam(required = false) String q,
			@RequestParam(required = false) String filter) {
		return service.list(q, filter);
	}

	@GetMapping("/conversations/{id}/messages")
	@Operation(summary = "A conversation's messages, oldest first", description = "markRead=true marks it read here and on the client's phone.")
	public List<MessageView> messages(@PathVariable long id, @RequestParam(defaultValue = "false") boolean markRead) {
		return service.messages(id, markRead);
	}

	@PostMapping("/conversations/{id}/messages")
	@Operation(summary = "Send a text", description = "409 when the 24-hour window is closed: send the template instead.")
	public MessageView send(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id, @RequestBody TextRequest request) {
		return service.sendText(user, id, request.text());
	}

	@PostMapping("/conversations/{id}/template")
	@Operation(summary = "Send the approved template", description = "The only message WhatsApp accepts after 24 hours without a reply.")
	public MessageView template(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id) {
		return service.sendTemplate(user, id);
	}

	@PostMapping(path = "/conversations/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Send a file (photo, video, audio or document, 16 MB at most), with an optional caption")
	public MessageView file(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@RequestPart("file") MultipartFile file, @RequestParam(required = false) String caption) {
		return service.sendFile(user, id, file, caption);
	}

	@GetMapping("/messages/{id}/media")
	@Operation(summary = "Download the file of a message")
	public ResponseEntity<byte[]> media(@PathVariable long id) {
		WhatsAppCloudApi.MediaFile file = service.media(id);
		String name = service.fileName(id);
		if (name == null) name = "whatsapp-" + id + extension(file.mime());
		// Always a download, never opened in our page: it comes from a stranger.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.header("X-Media-Type", file.mime())
			.body(file.bytes());
	}

	private static String extension(String mime) {
		return switch (mime == null ? "" : mime.split(";")[0]) {
			case "image/jpeg" -> ".jpg";
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			case "video/mp4" -> ".mp4";
			case "audio/ogg" -> ".ogg";
			case "audio/mpeg" -> ".mp3";
			case "application/pdf" -> ".pdf";
			default -> "";
		};
	}
}
