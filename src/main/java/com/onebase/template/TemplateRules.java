package com.onebase.template;

import com.onebase.common.ApiException;
import com.onebase.template.TemplateBlock.ButtonType;
import com.onebase.template.TemplateBlock.Type;
import com.onebase.template.TemplateDtos.BlockRequest;
import com.onebase.template.TemplateDtos.TemplateRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a template may hold — the same rules as the builder ({@code lib/templates/rules.ts}
 * in the frontend): change both together.
 * <ul>
 * <li>a name of 1–80 characters, a language (EN, FR, AR), a footer of 60 at most;</li>
 * <li>1–20 blocks, at least one text or file; each text written; all texts together
 *     {@value #MAX_TEXT} characters at most;</li>
 * <li>one kind of file per template: up to 2 photos (JPG, PNG), or 1 PDF, or 1 audio
 *     (MP3, OGG, AAC, M4A, AMR), 4 MB each — the type read from the bytes;</li>
 * <li>up to 2 buttons (quick reply, link, phone), 1 phone at most. A button belongs under
 *     the block before it, so the message can't start with one.</li>
 * </ul>
 * Refusals are 400 with a sentence the Admin can act on. Whether a file exists is the
 * service's job; whether the name is taken too.
 */
public final class TemplateRules {

	public static final int MAX_NAME = 80;
	public static final int MAX_FOOTER = 60;
	public static final int MAX_BLOCKS = 20;
	/** All the texts of a template together. */
	public static final int MAX_TEXT = 1024;
	public static final int MAX_BUTTONS = 2;
	public static final int MAX_BUTTON_LABEL = 20;
	public static final int MAX_BUTTON_VALUE = 2000;
	/** Per file: what Vercel lets through in one request. */
	public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

	/** EN / FR / AR → the name the screens show. */
	public static final Map<String, String> LANGUAGES = Map.of("EN", "English", "FR", "French", "AR", "Arabic");

	/** How many of each file a template may hold. */
	public static final Map<Type, Integer> MAX_FILES = Map.of(Type.IMAGE, 2, Type.DOCUMENT, 1, Type.AUDIO, 1);

	/** The types read from the bytes ({@link #sniff}), and the block each may fill. */
	public static final Map<String, Type> FILE_TYPES = Map.of(
		"image/jpeg", Type.IMAGE,
		"image/png", Type.IMAGE,
		"application/pdf", Type.DOCUMENT,
		"audio/mpeg", Type.AUDIO,
		"audio/ogg", Type.AUDIO,
		"audio/aac", Type.AUDIO,
		"audio/mp4", Type.AUDIO,
		"audio/amr", Type.AUDIO);

	private static final Map<Type, String> FILE_LABELS = Map.of(Type.IMAGE, "photo", Type.DOCUMENT, "PDF", Type.AUDIO, "audio");
	private static final Map<Type, String> FORMATS = Map.of(Type.IMAGE, "a JPG or PNG picture", Type.DOCUMENT, "a PDF",
		Type.AUDIO, "an MP3, OGG, AAC, M4A or AMR audio");

	private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
	private static final Pattern PHONE = Pattern.compile("\\+[0-9 ]*[0-9][0-9 ]*");

	private TemplateRules() {
	}

	/** A checked template, ready to be copied onto the entity. */
	public record Clean(String name, String language, boolean active, String footer, List<TemplateBlock> blocks) {
	}

	public static Clean check(TemplateRequest input) {
		if (input == null) throw refuse("The template is missing.");
		String name = input.name() == null ? "" : input.name().trim();
		if (name.isEmpty()) throw refuse("Enter the template's name.");
		if (name.length() > MAX_NAME) throw refuse("Keep the name under " + MAX_NAME + " characters.");
		String language = input.language() == null ? "" : input.language().trim().toUpperCase(Locale.ROOT);
		if (!LANGUAGES.containsKey(language)) throw refuse("Choose the language: English, French or Arabic.");
		String footer = input.footer() == null ? "" : input.footer().strip();
		if (footer.length() > MAX_FOOTER) throw refuse("Keep the footer under " + MAX_FOOTER + " characters.");
		boolean active = input.active() == null || input.active();
		return new Clean(name, language, active, footer, blocks(input.blocks()));
	}

	private static List<TemplateBlock> blocks(List<BlockRequest> input) {
		if (input == null || input.isEmpty()) throw refuse("The message is empty: add a text or a file.");
		if (input.size() > MAX_BLOCKS) throw refuse("A message can have " + MAX_BLOCKS + " blocks at most.");
		List<TemplateBlock> blocks = new ArrayList<>();
		Set<Long> files = new HashSet<>();
		int texts = 0;
		int length = 0;
		for (BlockRequest block : input) {
			Type type = typeOf(block);
			switch (type) {
				case TEXT -> {
					texts++;
					String text = block.text() == null ? "" : block.text().strip();
					if (text.isEmpty()) throw refuse("Text " + texts + " is empty: write it or remove it.");
					length += text.length();
					blocks.add(TemplateBlock.text(text));
				}
				case IMAGE, DOCUMENT, AUDIO -> {
					if (block.fileId() == null) {
						throw refuse("The " + FILE_LABELS.get(type) + " has no file: choose one or remove the block.");
					}
					if (!files.add(block.fileId())) throw refuse("The same file is used twice.");
					blocks.add(TemplateBlock.file(type, block.fileId()));
				}
				case BUTTON -> {
					if (blocks.isEmpty()) throw refuse("A button goes under a text or a file: the message can't start with one.");
					blocks.add(button(block));
				}
			}
		}
		if (length > MAX_TEXT) {
			throw refuse("The texts together can be " + MAX_TEXT + " characters at most (now " + length + ").");
		}
		checkCounts(blocks);
		return blocks;
	}

	private static Type typeOf(BlockRequest block) {
		String type = block == null || block.type() == null ? "" : block.type().trim().toUpperCase(Locale.ROOT);
		try {
			return Type.valueOf(type);
		} catch (IllegalArgumentException e) {
			throw refuse("A block of the message has an unknown type" + (type.isEmpty() ? "." : ": " + type + "."));
		}
	}

	/** Something to send besides buttons, one kind of file and how many, and the buttons. */
	private static void checkCounts(List<TemplateBlock> blocks) {
		Map<Type, Integer> counts = new EnumMap<>(Type.class);
		blocks.forEach(block -> counts.merge(block.getType(), 1, Integer::sum));
		if (counts.getOrDefault(Type.TEXT, 0) == 0 && blocks.stream().noneMatch(TemplateBlock::isFile)) {
			throw refuse("The message needs a text or a file, not only buttons.");
		}
		List<Type> kinds = MAX_FILES.keySet().stream().filter(counts::containsKey).toList();
		if (kinds.size() > 1) throw refuse("One kind of file per template: photos, or a PDF, or an audio.");
		for (Type kind : kinds) {
			int max = MAX_FILES.get(kind);
			if (counts.get(kind) > max) {
				throw refuse(max == 1 ? "One " + FILE_LABELS.get(kind) + " per template at most."
					: max + " " + FILE_LABELS.get(kind) + "s per template at most.");
			}
		}
		if (counts.getOrDefault(Type.BUTTON, 0) > MAX_BUTTONS) throw refuse(MAX_BUTTONS + " buttons per template at most.");
		long phones = blocks.stream().filter(block -> block.getButtonType() == ButtonType.PHONE).count();
		if (phones > 1) throw refuse("One phone button per template at most.");
	}

	private static TemplateBlock button(BlockRequest block) {
		String raw = block.buttonType() == null ? "" : block.buttonType().trim().toUpperCase(Locale.ROOT);
		ButtonType type;
		try {
			type = ButtonType.valueOf(raw);
		} catch (IllegalArgumentException e) {
			throw refuse("Choose the button's type: quick reply, link or phone.");
		}
		String label = block.label() == null ? "" : block.label().strip();
		if (label.isEmpty()) throw refuse("A button has no label: write one or remove the button.");
		if (label.length() > MAX_BUTTON_LABEL) {
			throw refuse("A button's label can be " + MAX_BUTTON_LABEL + " characters at most: “" + label + "”.");
		}
		String value = block.value() == null ? "" : block.value().trim();
		return switch (type) {
			case URL -> {
				if (!URL.matcher(value).matches() || value.length() > MAX_BUTTON_VALUE) {
					throw refuse("The button “" + label + "” needs a full link, starting with https://");
				}
				yield TemplateBlock.button(type, label, value);
			}
			case PHONE -> {
				if (!PHONE.matcher(value).matches() || value.length() > 30) {
					throw refuse("The button “" + label + "” needs a phone number starting with +, like +1 514 555 0100.");
				}
				yield TemplateBlock.button(type, label, value);
			}
			case QUICK_REPLY -> TemplateBlock.button(type, label, null);
		};
	}

	// ── Files ──────────────────────────────────────────────────────────────

	/** Refuses a file that can't fill this block (a PDF in a photo block, …). */
	public static void checkFile(Type block, String mime) {
		if (FILE_TYPES.get(mime) != block) {
			throw refuse("This file can't be used as the " + FILE_LABELS.get(block) + ": use " + FORMATS.get(block) + ".");
		}
	}

	/** Checks an upload: its size, and its type read from the bytes. Returns that type. */
	public static String checkUpload(byte[] bytes) {
		if (bytes == null || bytes.length == 0) throw refuse("Choose a file to upload.");
		if (bytes.length > MAX_FILE_BYTES) throw refuse("Files can be 4 MB at most.");
		String mime = sniff(bytes);
		if (mime == null) {
			throw refuse("This file can't be sent: use a JPG or PNG picture, a PDF, or an MP3, OGG, AAC, M4A or AMR audio.");
		}
		return mime;
	}

	/** The accepted formats, recognised by their first bytes; null for anything else. */
	public static String sniff(byte[] bytes) {
		if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
		if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
		if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) return "application/pdf";
		if (startsWith(bytes, 'I', 'D', '3') || isMpegFrame(bytes)) return "audio/mpeg";
		if (startsWith(bytes, 'O', 'g', 'g', 'S')) return "audio/ogg";
		if (startsWith(bytes, '#', '!', 'A', 'M', 'R')) return "audio/amr";
		// AAC in ADTS frames: 12 sync bits, then layer 00 (an MP3 frame has a non-zero layer).
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xF6) == 0xF0) return "audio/aac";
		// MPEG-4 audio: an "ftyp" box whose brand is M4A (what phones record voice notes in).
		if (bytes.length >= 11 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p'
				&& bytes[8] == 'M' && bytes[9] == '4' && bytes[10] == 'A') return "audio/mp4";
		return null;
	}

	/** An MP3 without tags starts straight with a frame: 11 sync bits and a layer other than 00. */
	private static boolean isMpegFrame(byte[] bytes) {
		return bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0 && (bytes[1] & 0x06) != 0;
	}

	private static boolean startsWith(byte[] bytes, int... signature) {
		if (bytes.length < signature.length) return false;
		for (int i = 0; i < signature.length; i++) {
			if ((bytes[i] & 0xFF) != signature[i]) return false;
		}
		return true;
	}

	/** The name a file was sent with, for display (and a PDF's name on the client's phone): no folders, 120 characters at most. */
	public static String fileName(String original) {
		String name = original == null ? "" : original.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
		if (name.isEmpty()) return "file";
		return name.length() > 120 ? name.substring(name.length() - 120) : name;
	}

	// ── Showing ────────────────────────────────────────────────────────────

	/** "1 text · 1 photo · 1 button": what a template holds, for the list and the Inbox picker. */
	public static String summary(List<TemplateBlock> blocks) {
		Map<Type, Integer> counts = new EnumMap<>(Type.class);
		blocks.forEach(block -> counts.merge(block.getType(), 1, Integer::sum));
		List<String> parts = new ArrayList<>();
		count(parts, counts.get(Type.TEXT), "text", "texts");
		count(parts, counts.get(Type.IMAGE), "photo", "photos");
		count(parts, counts.get(Type.DOCUMENT), "PDF", "PDFs");
		count(parts, counts.get(Type.AUDIO), "audio", "audios");
		count(parts, counts.get(Type.BUTTON), "button", "buttons");
		return String.join(" · ", parts);
	}

	private static void count(List<String> parts, Integer n, String one, String many) {
		if (n != null && n > 0) parts.add(n + " " + (n == 1 ? one : many));
	}

	static ApiException refuse(String message) {
		return ApiException.badRequest(message);
	}
}
