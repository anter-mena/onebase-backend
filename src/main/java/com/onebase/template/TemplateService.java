package com.onebase.template;

import com.onebase.actionlog.ActionLog.Action;
import com.onebase.actionlog.ActionLog.TargetType;
import com.onebase.actionlog.ActionLogService;
import com.onebase.common.ApiException;
import com.onebase.security.AuthPrincipal;
import com.onebase.template.TemplateDtos.BlockView;
import com.onebase.template.TemplateDtos.FileView;
import com.onebase.template.TemplateDtos.TemplateRequest;
import com.onebase.template.TemplateDtos.TemplateResponse;
import com.onebase.template.TemplateFileCleanup.TemplateSaved;
import com.onebase.user.User;
import com.onebase.user.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Communication → Templates: saved WhatsApp messages, built by Admins and sent from
 * the WhatsApp Inbox by anyone (see {@code WhatsAppService.sendSavedTemplate}).
 *
 * <p>No delete: a template is switched off, and the Inbox stops offering it. Files are
 * uploaded first, then a save points at them; the ones nothing uses any more are
 * deleted after the save ({@link TemplateFileCleanup}).
 *
 * <p>Every change is written to the Action log.
 */
@Service
public class TemplateService {

	private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

	/** A file's bytes, to send or show. */
	public record FileData(byte[] bytes, String mime, String filename) {
	}

	/** An active template, read once with everything a send needs (its files included). */
	public record Sendable(long id, String name, String footer, List<TemplateBlock> blocks, Map<Long, FileData> files) {
	}

	private final MessageTemplateRepository templates;
	private final TemplateFileRepository files;
	private final UserRepository users;
	private final ActionLogService actionLog;
	private final ApplicationEventPublisher events;

	public TemplateService(MessageTemplateRepository templates, TemplateFileRepository files, UserRepository users,
			ActionLogService actionLog, ApplicationEventPublisher events) {
		this.templates = templates;
		this.files = files;
		this.users = users;
		this.actionLog = actionLog;
		this.events = events;
	}

	// ── Reading ─────────────────────────────────────────────────────────

	/** Newest change first. {@code q}: words of the name or the texts. {@code active}: null = all. */
	@Transactional(readOnly = true)
	public List<TemplateResponse> list(String q, Boolean active) {
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		List<MessageTemplate> found = templates.findAllByOrderByUpdatedAtDescIdDesc().stream()
			.filter(t -> active == null || t.isActive() == active)
			.filter(t -> needle.isEmpty() || matches(t, needle))
			.toList();
		return responses(found);
	}

	/** What the Inbox offers: the active templates, newest change first. */
	@Transactional(readOnly = true)
	public List<TemplateResponse> listActive() {
		return responses(templates.findByActiveTrueOrderByUpdatedAtDescIdDesc());
	}

	@Transactional(readOnly = true)
	public TemplateResponse get(long id) {
		return responses(List.of(find(id))).getFirst();
	}

	@Transactional(readOnly = true)
	public FileData file(long id) {
		TemplateFile file = files.findById(id).orElseThrow(() -> ApiException.notFound("This file does not exist."));
		return new FileData(file.getData(), file.getMime(), file.getFilename());
	}

	/** A file the Inbox may show: only one an active template uses. */
	@Transactional(readOnly = true)
	public FileData activeFile(long id) {
		if (!files.usedByActiveTemplate(id)) throw ApiException.notFound("This file does not exist.");
		return file(id);
	}

	/** 404 if it does not exist, 400 if it is switched off. */
	@Transactional(readOnly = true)
	public Sendable forSending(long id) {
		MessageTemplate template = find(id);
		if (!template.isActive()) throw ApiException.badRequest("This template is switched off: it can't be sent.");
		Map<Long, FileData> data = new HashMap<>();
		for (TemplateBlock block : template.getBlocks()) {
			if (block.isFile()) data.put(block.getFileId(), file(block.getFileId()));
		}
		return new Sendable(template.getId(), template.getName(), template.getFooter(), List.copyOf(template.getBlocks()),
			data);
	}

	// ── Changing ────────────────────────────────────────────────────────

	/** Stores an upload for the builder. Its type is read from the bytes; 4 MB at most. */
	@Transactional
	public FileView upload(MultipartFile upload) {
		if (upload == null || upload.isEmpty()) throw ApiException.badRequest("Choose a file to upload.");
		if (upload.getSize() > TemplateRules.MAX_FILE_BYTES) throw ApiException.badRequest("Files can be 4 MB at most.");
		byte[] bytes;
		try {
			bytes = upload.getBytes();
		} catch (IOException e) {
			throw ApiException.badRequest("The file could not be read. Please choose it again.");
		}
		String mime = TemplateRules.checkUpload(bytes);
		TemplateFile file = files.save(new TemplateFile(bytes, mime, TemplateRules.fileName(upload.getOriginalFilename())));
		return new FileView(file.getId(), file.getFilename(), file.getMime(), file.getSizeBytes());
	}

	@Transactional
	public TemplateResponse create(AuthPrincipal admin, TemplateRequest request) {
		TemplateRules.Clean clean = TemplateRules.check(request);
		checkFiles(clean.blocks());
		refuseTaken(clean.name(), clean.language(), null);
		User actor = actor(admin);

		MessageTemplate template = new MessageTemplate(actor.getId());
		template.setContent(clean.name(), clean.language(), clean.footer(), clean.blocks(), actor.getId());
		template.setActive(clean.active());
		template = save(template);

		String summary = TemplateRules.summary(template.getBlocks());
		actionLog.record(actor, Action.CREATED, TargetType.TEMPLATE, template.getId(), template.getName(),
			"Added a template in " + TemplateRules.LANGUAGES.get(template.getLanguage()) + " (" + summary + ")"
				+ (template.isActive() ? "" : ", switched off"));
		log.info("User id={} added template id={}", admin.userId(), template.getId());
		events.publishEvent(new TemplateSaved(Set.of()));
		return get(template.getId());
	}

	@Transactional
	public TemplateResponse update(AuthPrincipal admin, long id, TemplateRequest request) {
		MessageTemplate template = find(id);
		TemplateRules.Clean clean = TemplateRules.check(request);
		checkFiles(clean.blocks());
		refuseTaken(clean.name(), clean.language(), template.getId());
		User actor = actor(admin);

		List<String> changed = new ArrayList<>();
		if (!template.getName().equals(clean.name())) changed.add("name from " + template.getName() + " to " + clean.name());
		if (!template.getLanguage().equals(clean.language())) {
			changed.add("language to " + TemplateRules.LANGUAGES.get(clean.language()));
		}
		if (!sameBlocks(template.getBlocks(), clean.blocks())) changed.add("message");
		if (!template.getFooter().equals(clean.footer())) changed.add("footer");

		Set<Long> before = fileIds(template.getBlocks());
		if (!changed.isEmpty()) {
			template.setContent(clean.name(), clean.language(), clean.footer(), clean.blocks(), actor.getId());
			actionLog.record(actor, Action.UPDATED, TargetType.TEMPLATE, template.getId(), clean.name(),
				"Changed " + String.join(", ", changed));
		}
		if (template.isActive() != clean.active()) switchTo(actor, template, clean.active());
		save(template);
		events.publishEvent(new TemplateSaved(before));
		return get(template.getId());
	}

	/** On / off. Not a content change: {@code updatedAt} stays. */
	@Transactional
	public TemplateResponse setActive(AuthPrincipal admin, long id, boolean active) {
		MessageTemplate template = find(id);
		if (template.isActive() != active) switchTo(actor(admin), template, active);
		return get(template.getId());
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private void switchTo(User actor, MessageTemplate template, boolean active) {
		template.setActive(active);
		actionLog.record(actor, active ? Action.ACTIVATED : Action.DEACTIVATED, TargetType.TEMPLATE, template.getId(),
			template.getName(),
			active ? "Template switched on: it can be sent from the WhatsApp Inbox"
				: "Template switched off: it is no longer offered in the WhatsApp Inbox; nothing was deleted");
	}

	private MessageTemplate find(long id) {
		return templates.findById(id).orElseThrow(() -> ApiException.notFound("This template does not exist."));
	}

	/** Every file exists (uploaded through {@code /api/templates/files}) and fits its block. */
	private void checkFiles(List<TemplateBlock> blocks) {
		Set<Long> ids = fileIds(blocks);
		if (ids.isEmpty()) return;
		Map<Long, TemplateFileRepository.Info> found = infos(ids);
		for (TemplateBlock block : blocks) {
			if (!block.isFile()) continue;
			TemplateFileRepository.Info info = found.get(block.getFileId());
			if (info == null) throw ApiException.badRequest("A file of this template is no longer available: upload it again.");
			TemplateRules.checkFile(block.getType(), info.getMime());
		}
	}

	private void refuseTaken(String name, String language, Long allowedId) {
		templates.findByNameIgnoreCaseAndLanguage(name, language)
			.filter(existing -> !existing.getId().equals(allowedId))
			.ifPresent(existing -> {
				throw taken(existing.getName(), language);
			});
	}

	private static ApiException taken(String name, String language) {
		return ApiException.conflict("A template named “" + name + "” already exists in "
			+ TemplateRules.LANGUAGES.get(language) + ".");
	}

	/** Two Admins saving the same name at the same moment: the database has the last word. */
	private MessageTemplate save(MessageTemplate template) {
		try {
			return templates.saveAndFlush(template);
		} catch (DataIntegrityViolationException e) {
			throw taken(template.getName(), template.getLanguage());
		}
	}

	private static boolean matches(MessageTemplate template, String needle) {
		if (template.getName().toLowerCase(Locale.ROOT).contains(needle)) return true;
		return template.getBlocks().stream()
			.anyMatch(block -> block.getText() != null && block.getText().toLowerCase(Locale.ROOT).contains(needle));
	}

	private static boolean sameBlocks(List<TemplateBlock> a, List<TemplateBlock> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			TemplateBlock x = a.get(i);
			TemplateBlock y = b.get(i);
			if (x.getType() != y.getType() || !Objects.equals(x.getText(), y.getText())
					|| !Objects.equals(x.getFileId(), y.getFileId()) || x.getButtonType() != y.getButtonType()
					|| !Objects.equals(x.getButtonLabel(), y.getButtonLabel())
					|| !Objects.equals(x.getButtonValue(), y.getButtonValue())) {
				return false;
			}
		}
		return true;
	}

	private static Set<Long> fileIds(Collection<TemplateBlock> blocks) {
		return blocks.stream().filter(TemplateBlock::isFile).map(TemplateBlock::getFileId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Map<Long, TemplateFileRepository.Info> infos(Collection<Long> ids) {
		return files.findInfoByIdIn(ids).stream()
			.collect(Collectors.toMap(TemplateFileRepository.Info::getId, Function.identity()));
	}

	/** The responses, with one query for the files and one for the people who changed them. */
	private List<TemplateResponse> responses(List<MessageTemplate> list) {
		Set<Long> ids = new LinkedHashSet<>();
		Set<Long> editors = new LinkedHashSet<>();
		for (MessageTemplate template : list) {
			ids.addAll(fileIds(template.getBlocks()));
			if (template.getUpdatedBy() != null) editors.add(template.getUpdatedBy());
		}
		Map<Long, TemplateFileRepository.Info> found = ids.isEmpty() ? Map.of() : infos(ids);
		Map<Long, String> names = editors.isEmpty() ? Map.of()
			: users.findAllById(editors).stream().collect(Collectors.toMap(User::getId, User::getFullName));
		return list.stream().map(template -> response(template, found, names)).toList();
	}

	private static TemplateResponse response(MessageTemplate template, Map<Long, TemplateFileRepository.Info> found,
			Map<Long, String> names) {
		List<BlockView> blocks = template.getBlocks().stream().map(block -> switch (block.getType()) {
			case TEXT -> new BlockView(block.getType().name(), block.getText(), null, null, null, null);
			case IMAGE, DOCUMENT, AUDIO -> {
				TemplateFileRepository.Info info = found.get(block.getFileId());
				FileView file = info == null ? new FileView(block.getFileId(), "file", "application/octet-stream", 0)
					: new FileView(info.getId(), info.getFilename(), info.getMime(), info.getSizeBytes());
				yield new BlockView(block.getType().name(), null, file, null, null, null);
			}
			case BUTTON -> new BlockView(block.getType().name(), null, null, block.getButtonType().name(),
				block.getButtonLabel(), block.getButtonValue());
		}).toList();
		return new TemplateResponse(template.getId(), template.getName(), template.getLanguage(), template.isActive(),
			template.getFooter(), blocks, TemplateRules.summary(template.getBlocks()), template.getCreatedAt(),
			template.getUpdatedAt(), template.getUpdatedBy() == null ? null : names.get(template.getUpdatedBy()));
	}

	private User actor(AuthPrincipal principal) {
		return users.findById(principal.userId())
			.orElseThrow(() -> ApiException.unauthorized("Please sign in to continue."));
	}
}
