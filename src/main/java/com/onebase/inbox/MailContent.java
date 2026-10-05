package com.onebase.inbox;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * What a message says, as plain text, and which files it carries.
 *
 * <p>⚠️ <b>Plain text only, on purpose.</b> HTML from strangers is never sent to
 * the browser: an HTML-only email is turned into text here (links keep their
 * address in brackets), so the reading pane cannot run anybody's markup.
 *
 * <p>Attachments are numbered in the order {@link #walk} meets them; the same
 * walk finds the file again for a download, so the number is stable for a
 * message.
 */
final class MailContent {

	/** One attached file. {@code size} is the encoded size Gmail reports, close enough to show. */
	record Attachment(int index, String name, String contentType, long size) {
	}

	record Parsed(String text, List<Attachment> attachments) {

		/** The new words only: the quoted original of a reply ("> …" and its "… wrote:" line) is left out. */
		String snippet() {
			String flat = withoutQuote(text).replaceAll("\\s+", " ").trim();
			if (flat.isEmpty()) flat = text.replaceAll("\\s+", " ").trim();
			return flat.length() > 200 ? flat.substring(0, 200) : flat;
		}
	}

	private MailContent() {
	}

	/** "On … wrote:" in English, "Le … a écrit :" from a French Gmail. */
	static boolean isQuoteHeader(String line) {
		return line.matches("(?i)^\\s*(on|le)\\s.+(wrote|a écrit)\\s*:\\s*$");
	}

	static String withoutQuote(String text) {
		StringBuilder out = new StringBuilder();
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.startsWith(">")) continue;
			if (isQuoteHeader(line) && i + 1 < lines.length && (lines[i + 1].startsWith(">") || lines[i + 1].isBlank())) continue;
			out.append(line).append('\n');
		}
		return out.toString();
	}

	static Parsed parse(Part message) throws MessagingException, IOException {
		Walk walk = new Walk();
		walk(message, walk, -1);
		String text = walk.plain != null ? walk.plain : walk.html != null ? htmlToText(walk.html) : "";
		return new Parsed(tidy(text), List.copyOf(walk.attachments));
	}

	/** The bytes of attachment number {@code index}, or null if the message has no such file. */
	static Part attachmentPart(Part message, int index) throws MessagingException, IOException {
		Walk walk = new Walk();
		walk.wanted = index;
		walk(message, walk, -1);
		return walk.found;
	}

	static byte[] read(Part part, long maxBytes) throws MessagingException, IOException {
		try (InputStream in = part.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[16_384];
			long total = 0;
			int n;
			while ((n = in.read(buffer)) != -1) {
				total += n;
				if (total > maxBytes) {
					throw new IOException("Attachment larger than " + maxBytes + " bytes");
				}
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	private static final class Walk {
		String plain;
		String html;
		final List<Attachment> attachments = new ArrayList<>();
		int wanted = -1;
		Part found;
	}

	private static void walk(Part part, Walk walk, int depth) throws MessagingException, IOException {
		if (depth > 20) {
			return;
		}
		String type = baseType(part);
		if (type.startsWith("multipart/") && part.getContent() instanceof Multipart multipart) {
			if (type.equals("multipart/alternative")) {
				walkAlternative(multipart, walk, depth);
				return;
			}
			for (int i = 0; i < multipart.getCount(); i++) {
				walk(multipart.getBodyPart(i), walk, depth + 1);
			}
			return;
		}
		boolean isFile = isAttachment(part, type);
		if (!isFile && type.equals("text/plain") && walk.plain == null) {
			walk.plain = textOf(part);
			return;
		}
		if (!isFile && type.equals("text/html") && walk.html == null) {
			walk.html = textOf(part);
			return;
		}
		if (isFile || !type.startsWith("text/")) {
			// Inline images of an HTML newsletter are files too: listed, never shown.
			int index = walk.attachments.size();
			String name = fileName(part, type, index);
			walk.attachments.add(new Attachment(index, name, type, Math.max(part.getSize(), 0)));
			if (index == walk.wanted) {
				walk.found = part;
			}
		}
	}

	/** Prefer the text version; fall back to HTML; files inside an alternative still count. */
	private static void walkAlternative(Multipart multipart, Walk walk, int depth) throws MessagingException, IOException {
		BodyPart plain = null;
		BodyPart html = null;
		BodyPart nested = null;
		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart child = multipart.getBodyPart(i);
			String type = baseType(child);
			if (type.equals("text/plain") && plain == null) plain = child;
			else if (type.equals("text/html") && html == null) html = child;
			else if (type.startsWith("multipart/") && nested == null) nested = child;
		}
		if (plain != null && walk.plain == null) walk.plain = textOf(plain);
		if (html != null && walk.html == null) walk.html = textOf(html);
		if (nested != null) walk(nested, walk, depth + 1);
	}

	private static boolean isAttachment(Part part, String type) throws MessagingException {
		String disposition = part.getDisposition();
		if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) return true;
		return part.getFileName() != null && !type.equals("text/plain") && !type.equals("text/html")
			|| type.equals("message/rfc822");
	}

	private static String fileName(Part part, String type, int index) throws MessagingException {
		String name = part.getFileName();
		if (name != null) {
			try {
				name = MimeUtility.decodeText(name);
			} catch (IOException ignored) {
				// keep the raw name
			}
			name = name.replaceAll("[\\r\\n\\\\/]", "_").trim();
			if (!name.isEmpty()) return name;
		}
		if (type.equals("message/rfc822")) return "message-" + (index + 1) + ".eml";
		String ext = type.contains("/") ? type.substring(type.indexOf('/') + 1).replaceAll("[^a-z0-9]", "") : "bin";
		return "attachment-" + (index + 1) + "." + (ext.isEmpty() ? "bin" : ext);
	}

	private static String baseType(Part part) throws MessagingException {
		String raw = part.getContentType();
		if (raw == null) return "text/plain";
		try {
			return new ContentType(raw).getBaseType().toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			return raw.toLowerCase(Locale.ROOT).split(";")[0].trim();
		}
	}

	private static String textOf(Part part) throws MessagingException, IOException {
		Object content;
		try {
			content = part.getContent();
		} catch (IOException | MessagingException e) {
			// An unknown charset: read the bytes as UTF-8 rather than lose the message.
			return new String(read(part, 5_000_000), java.nio.charset.StandardCharsets.UTF_8);
		}
		return content instanceof String s ? s : "";
	}

	/** HTML to readable text: block elements become line breaks, links keep their address. */
	static String htmlToText(String html) {
		Document doc = Jsoup.parse(html);
		doc.select("script, style, head, title").remove();
		StringBuilder out = new StringBuilder();
		Element body = doc.body();
		if (body != null) {
			appendText(body, out);
		}
		return out.toString();
	}

	private static void appendText(Node node, StringBuilder out) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode text) {
				out.append(text.text());
			} else if (child instanceof Element el) {
				String tag = el.normalName();
				boolean block = switch (tag) {
					case "p", "div", "tr", "li", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article", "header", "footer" -> true;
					default -> false;
				};
				if (tag.equals("br")) {
					out.append('\n');
					continue;
				}
				if (block) out.append('\n');
				appendText(el, out);
				if (tag.equals("a")) {
					String href = el.attr("href");
					if (href.startsWith("http") && !el.text().contains(href)) {
						out.append(" [").append(href).append(']');
					}
				}
				if (tag.equals("td")) out.append(' ');
				if (block) out.append('\n');
			}
		}
	}

	/** Same line endings everywhere, no runs of empty lines, no trailing spaces. */
	static String tidy(String text) {
		String unified = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
		StringBuilder out = new StringBuilder();
		int blank = 0;
		for (String line : unified.split("\n", -1)) {
			String trimmed = line.stripTrailing();
			if (trimmed.isBlank()) {
				blank++;
				if (blank > 1) continue;
				out.append('\n');
			} else {
				blank = 0;
				out.append(trimmed).append('\n');
			}
		}
		return out.toString().strip();
	}
}
