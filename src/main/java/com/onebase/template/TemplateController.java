package com.onebase.template;

import com.onebase.security.AuthPrincipal;
import com.onebase.template.TemplateDtos.FileView;
import com.onebase.template.TemplateDtos.StatusRequest;
import com.onebase.template.TemplateDtos.TemplateRequest;
import com.onebase.template.TemplateDtos.TemplateResponse;
import com.onebase.template.TemplateService.FileData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Communication → Templates. Admins only: nothing opens {@code /api/templates/**} in
 * {@code SecurityConfig}, so it falls under its Admin-only rule. The Inbox reads and
 * sends the active ones through {@code /api/whatsapp/templates} instead.
 */
@RestController
@RequestMapping("/api/templates")
@Tag(name = "Templates", description = "Saved WhatsApp messages (texts, files, buttons), sent from the WhatsApp Inbox. Admins only. No delete — switch off instead.")
public class TemplateController {

	private final TemplateService service;

	public TemplateController(TemplateService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Every template, newest change first", description = "q: words of the name or the texts. active: true, false, or left out for all.")
	public List<TemplateResponse> list(@RequestParam(required = false) String q, @RequestParam(required = false) Boolean active) {
		return service.list(q, active);
	}

	@GetMapping("/{id}")
	@Operation(summary = "One template")
	public TemplateResponse get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	@Operation(summary = "Add a template", description = "400 when a rule is broken (said in a sentence). 409 if the name is taken in that language.")
	public ResponseEntity<TemplateResponse> create(@AuthenticationPrincipal AuthPrincipal admin, @RequestBody TemplateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(admin, request));
	}

	@PutMapping("/{id}")
	@Operation(summary = "Edit a template", description = "The whole template; the message is rewritten as sent. Logged, saying what changed.")
	public TemplateResponse update(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@RequestBody TemplateRequest request) {
		return service.update(admin, id, request);
	}

	@PatchMapping("/{id}/status")
	@Operation(summary = "Switch a template on or off", description = "Off: the Inbox stops offering it. Nothing is deleted.")
	public TemplateResponse setStatus(@AuthenticationPrincipal AuthPrincipal admin, @PathVariable long id,
			@Valid @RequestBody StatusRequest request) {
		return service.setActive(admin, id, request.active());
	}

	@PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Upload a photo, PDF or audio for a template",
		description = "JPG, PNG, PDF, MP3, OGG, AAC, M4A or AMR — read from the bytes, not the name. 4 MB at most. Unused after an hour, it is deleted.")
	public FileView upload(@RequestPart("file") MultipartFile file) {
		return service.upload(file);
	}

	@GetMapping("/files/{id}")
	@Operation(summary = "A template file's bytes, for the builder's preview")
	public ResponseEntity<byte[]> file(@PathVariable long id) {
		return serve(service.file(id));
	}

	/** Served as exactly what it is (its type was read from the bytes), never run as a page. */
	public static ResponseEntity<byte[]> serve(FileData file) {
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.mime()))
			.cacheControl(CacheControl.noCache().cachePrivate())
			.header(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.inline().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'; sandbox")
			.body(file.bytes());
	}
}
