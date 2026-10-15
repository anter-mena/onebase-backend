package com.onebase.whatsapp;

import com.onebase.template.TemplateBlock;
import com.onebase.template.TemplateBlock.ButtonType;
import com.onebase.template.TemplateBlock.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a saved template into the WhatsApp messages one client receives, in order
 * (ported from Yalla Tour's {@code FollowupMessages}, without its variables and call
 * page). WhatsApp has no message made of free blocks, so:
 * <ul>
 * <li>each text, photo, PDF or audio is its own message, in the template's order;</li>
 * <li>the buttons ride on the block just before them, as one interactive message: under
 *     a text, that text is its body; under a photo or PDF, that file is its header (and
 *     the text just before it, if any, its body — else "More info:"); under an audio
 *     (which takes no button), they go on the last text;</li>
 * <li>quick replies win over a link: WhatsApp shows reply buttons or one link, never
 *     both. What does not fit, and every phone button, becomes a line of the body,
 *     "Label: value" (WhatsApp makes numbers and links tappable);</li>
 * <li>the footer is the interactive message's footer, else the last line of the last text.</li>
 * </ul>
 * Pure: no Meta, no database. Files stay ids here; {@code WhatsAppService} uploads them.
 */
public final class TemplateMessages {

	/** WhatsApp's limits. */
	static final int MAX_BODY = 1024;
	static final int MAX_TEXT = 4096;
	static final int MAX_BUTTON_TITLE = 20;
	static final int MAX_FOOTER = 60;

	/** Body of an interactive message with no text to carry the buttons (WhatsApp requires one). */
	static final String NO_TEXT_BODY = "More info:";

	private TemplateMessages() {
	}

	/** One message to send. {@link #shown()} is what the conversation keeps of it. */
	public sealed interface Outgoing permits Text, Media, Interactive {
		String shown();
	}

	public record Text(String body) implements Outgoing {
		@Override
		public String shown() {
			return body;
		}
	}

	/** A photo, PDF or audio; {@code caption} may be null (an audio never has one). */
	public record Media(Type type, long fileId, String caption) implements Outgoing {

		/** Meta's media kind: image, document or audio. */
		public String kind() {
			return type.name().toLowerCase(Locale.ROOT);
		}

		@Override
		public String shown() {
			return caption;
		}
	}

	/** A button that opens a link. */
	public record Link(String label, String url) {
	}

	/**
	 * A text (and maybe a photo or PDF on top) with buttons: reply buttons, or else one link.
	 * {@code header} has no caption.
	 */
	public record Interactive(String body, Media header, List<String> replies, Link link, String footer) implements Outgoing {

		/** WhatsApp's {@code interactive} object, with the header's media id once Meta has the file. */
		public Map<String, Object> payload(String headerMediaId, String headerFilename) {
			Map<String, Object> interactive = new LinkedHashMap<>();
			interactive.put("type", replies.isEmpty() ? "cta_url" : "button");
			if (header != null) {
				Map<String, Object> media = new LinkedHashMap<>();
				media.put("id", headerMediaId);
				if (header.type() == Type.DOCUMENT && headerFilename != null) media.put("filename", headerFilename);
				Map<String, Object> top = new LinkedHashMap<>();
				top.put("type", header.kind());
				top.put(header.kind(), media);
				interactive.put("header", top);
			}
			interactive.put("body", Map.of("text", body));
			if (!footer.isEmpty()) interactive.put("footer", Map.of("text", cut(footer, MAX_FOOTER)));
			if (replies.isEmpty()) {
				interactive.put("action", Map.of("name", "cta_url",
					"parameters", Map.of("display_text", cut(link.label(), MAX_BUTTON_TITLE), "url", link.url())));
			} else {
				List<Map<String, Object>> buttons = new ArrayList<>();
				for (int i = 0; i < replies.size(); i++) {
					buttons.add(Map.of("type", "reply",
						"reply", Map.of("id", "reply_" + (i + 1), "title", cut(replies.get(i), MAX_BUTTON_TITLE))));
				}
				interactive.put("action", Map.of("buttons", buttons));
			}
			return interactive;
		}

		/** The conversation shows the text, then a line "[Label]" per button the client saw. */
		@Override
		public String shown() {
			StringBuilder shown = new StringBuilder(paragraphs(body, footer));
			List<String> labels = replies.isEmpty() ? List.of(link.label()) : replies;
			for (String label : labels) shown.append('\n').append('[').append(cut(label, MAX_BUTTON_TITLE)).append(']');
			return cut(shown.toString(), MAX_TEXT);
		}
	}

	public static List<Outgoing> build(List<TemplateBlock> blocks, String footerText) {
		List<TemplateBlock> parts = new ArrayList<>();
		List<String> replies = new ArrayList<>();
		List<TemplateBlock> others = new ArrayList<>(); // links and phones, in the template's order
		// The block the buttons sit under in the builder: the one just before the first button
		int anchor = -1;
		boolean seenButton = false;
		for (TemplateBlock block : blocks) {
			if (block.getType() != Type.BUTTON) {
				parts.add(block);
				continue;
			}
			if (!seenButton) {
				anchor = parts.size() - 1;
				seenButton = true;
			}
			if (block.getButtonType() == ButtonType.QUICK_REPLY) replies.add(block.getButtonLabel());
			else others.add(block);
		}
		// Reply buttons, or else one link; what does not fit (and every phone) becomes a line "Label: value".
		TemplateBlock linkBlock = replies.isEmpty()
			? others.stream().filter(b -> b.getButtonType() == ButtonType.URL).findFirst().orElse(null)
			: null;
		Link link = linkBlock == null ? null : new Link(linkBlock.getButtonLabel(), linkBlock.getButtonValue());
		String lines = String.join("\n", others.stream().filter(b -> b != linkBlock)
			.map(b -> b.getButtonLabel() + ": " + b.getButtonValue()).toList());
		String footer = footerText == null ? "" : footerText.strip();

		List<Outgoing> out = new ArrayList<>();
		if (replies.isEmpty() && link == null) {
			// No interactive message: the lines and the footer end the last text (or the last caption).
			String extra = paragraphs(lines, footer);
			int carrier = lastText(parts);
			int last = carrier >= 0 ? carrier : lastCaptioned(parts);
			for (int i = 0; i < parts.size(); i++) out.add(single(parts.get(i), i == last ? extra : ""));
			if (last < 0 && !extra.isEmpty()) out.add(text(extra));
			return out;
		}

		// Which parts become the interactive message: a text (its body) and/or a photo or PDF (its header).
		int bodyIndex = -1;
		int headerIndex = -1;
		TemplateBlock anchorPart = anchor >= 0 ? parts.get(anchor) : null;
		if (anchorPart != null && anchorPart.getType() == Type.TEXT) {
			bodyIndex = anchor;
		} else if (anchorPart != null && canBeHeader(anchorPart)) {
			// Under a photo or PDF: the file on top, the text just before it as the words.
			headerIndex = anchor;
			if (anchor > 0 && parts.get(anchor - 1).getType() == Type.TEXT) bodyIndex = anchor - 1;
		} else {
			// Under an audio: on the last text (with no text, a message of their own at the end).
			bodyIndex = lastText(parts);
		}
		String body = bodyIndex >= 0 ? parts.get(bodyIndex).getText() : NO_TEXT_BODY;
		body = cut(paragraphs(body, lines), MAX_BODY);
		Media header = headerIndex >= 0 ? new Media(parts.get(headerIndex).getType(), parts.get(headerIndex).getFileId(), null) : null;
		Interactive message = new Interactive(body, header, List.copyOf(replies), link, footer);

		int at = bodyIndex < 0 ? headerIndex : headerIndex < 0 ? bodyIndex : Math.min(bodyIndex, headerIndex);
		for (int i = 0; i < parts.size(); i++) {
			if (i == at) out.add(message);
			if (i != bodyIndex && i != headerIndex) out.add(single(parts.get(i), ""));
		}
		if (at < 0) out.add(message);
		return out;
	}

	private static boolean canBeHeader(TemplateBlock block) {
		return block.getType() == Type.IMAGE || block.getType() == Type.DOCUMENT;
	}

	private static int lastText(List<TemplateBlock> parts) {
		for (int i = parts.size() - 1; i >= 0; i--) {
			if (parts.get(i).getType() == Type.TEXT) return i;
		}
		return -1;
	}

	/** The last photo or PDF (the parts that take a caption), or -1. */
	private static int lastCaptioned(List<TemplateBlock> parts) {
		for (int i = parts.size() - 1; i >= 0; i--) {
			if (canBeHeader(parts.get(i))) return i;
		}
		return -1;
	}

	private static Outgoing single(TemplateBlock block, String extra) {
		if (block.getType() == Type.TEXT) return text(paragraphs(block.getText(), extra));
		String caption = extra.isEmpty() || block.getType() == Type.AUDIO ? null : cut(extra, MAX_BODY);
		return new Media(block.getType(), block.getFileId(), caption);
	}

	private static Text text(String body) {
		return new Text(cut(body, MAX_TEXT));
	}

	/** The non-empty pieces, a blank line apart. */
	static String paragraphs(String... pieces) {
		List<String> kept = new ArrayList<>();
		for (String piece : pieces) {
			if (piece != null && !piece.isBlank()) kept.add(piece);
		}
		return String.join("\n\n", kept);
	}

	static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}
}
