package com.onebase.template;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** What crosses the wire for Templates (and the Inbox's template picker). */
public final class TemplateDtos {

	private TemplateDtos() {
	}

	/** A photo, PDF or audio: {@code size} in bytes. */
	public record FileView(long id, String name, String mime, long size) {
	}

	/**
	 * One block of the message. Only the fields of its type are filled:
	 * TEXT → {@code text}; IMAGE / DOCUMENT / AUDIO → {@code file};
	 * BUTTON → {@code buttonType} (QUICK_REPLY, URL, PHONE), {@code label}, {@code value} (null for QUICK_REPLY).
	 */
	public record BlockView(String type, String text, FileView file, String buttonType, String label, String value) {
	}

	/**
	 * A template, as the list, the builder and the Inbox show it.
	 *
	 * @param summary       e.g. "1 text · 1 photo · 1 button"
	 * @param updatedByName who last changed the content, or null
	 */
	public record TemplateResponse(
			long id,
			String name,
			String language,
			boolean active,
			String footer,
			List<BlockView> blocks,
			String summary,
			Instant createdAt,
			Instant updatedAt,
			String updatedByName) {
	}

	/** Create and Edit send the whole template; {@link TemplateRules} checks it. */
	public record TemplateRequest(String name, String language, Boolean active, String footer, List<BlockRequest> blocks) {
	}

	/** {@code fileId}: from {@code POST /api/templates/files}. */
	public record BlockRequest(String type, String text, Long fileId, String buttonType, String label, String value) {
	}

	public record StatusRequest(@NotNull(message = "Say whether the template is active.") Boolean active) {
	}
}
