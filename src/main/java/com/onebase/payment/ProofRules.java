package com.onebase.payment;

import com.onebase.common.ApiException;
import com.onebase.template.TemplateRules;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * The proof files a payment is added with (2026-10-28): what an invoice or a receipt
 * comes as. Each type is read from the bytes; the name's extension only tells apart
 * formats that share their first bytes (a DOCX and an XLSX are both ZIPs, a CSV and
 * a TXT are both text). Anything that can run in a browser (HTML, SVG) is refused.
 */
public final class ProofRules {

	public static final int MAX_FILES = 5;
	/** All files together. The frontend's proxy buffers 10 MB of a request at most. */
	public static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;

	static final String FORMATS = "a PDF, a picture (JPG, PNG, WebP, HEIC, GIF, TIFF, BMP), a Word, Excel or OpenDocument file, "
		+ "or a CSV, TXT, XML, RTF or email (EML, MSG) file";

	/** ZIP containers, told apart by their extension. */
	private static final Map<String, String> ZIPPED = Map.of(
		"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
		"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
		"odt", "application/vnd.oasis.opendocument.text",
		"ods", "application/vnd.oasis.opendocument.spreadsheet");
	/** Old Office (OLE) files, told apart by their extension. */
	private static final Map<String, String> OLE = Map.of(
		"doc", "application/msword",
		"xls", "application/vnd.ms-excel",
		"msg", "application/vnd.ms-outlook");
	/** Plain text, told apart by their extension. */
	private static final Map<String, String> TEXT = Map.of(
		"csv", "text/csv",
		"txt", "text/plain",
		"xml", "application/xml",
		"eml", "message/rfc822");
	private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");

	private ProofRules() {
	}

	/** A file that passed: its bytes, its type, and a safe name. */
	public record Checked(byte[] data, String mime, String filename) {
	}

	/** At least one file, five at most, 10 MB together, each of a known format. */
	public static List<Checked> check(List<MultipartFile> files) {
		List<MultipartFile> given = files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
		if (given.isEmpty()) throw ApiException.badRequest("Add the proof of payment: " + FORMATS + ".");
		if (given.size() > MAX_FILES) throw ApiException.badRequest("Add " + MAX_FILES + " files at most.");
		long total = given.stream().mapToLong(MultipartFile::getSize).sum();
		if (total > MAX_TOTAL_BYTES) throw ApiException.badRequest("The files can be 10 MB together at most.");

		List<Checked> checked = new ArrayList<>();
		for (MultipartFile file : given) {
			String name = TemplateRules.fileName(file.getOriginalFilename());
			byte[] bytes;
			try {
				bytes = file.getBytes();
			} catch (IOException e) {
				throw ApiException.badRequest("\"" + name + "\" could not be read. Please add it again.");
			}
			String mime = sniff(bytes, name);
			if (mime == null) throw ApiException.badRequest("\"" + name + "\" can't be used as a proof: use " + FORMATS + ".");
			checked.add(new Checked(bytes, mime, name));
		}
		return checked;
	}

	/** The accepted formats, recognised by their first bytes (and the extension where they share them); null otherwise. */
	public static String sniff(byte[] bytes, String filename) {
		if (bytes == null || bytes.length == 0) return null;
		String ext = extension(filename);
		if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) return "application/pdf";
		if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
		if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
		if (startsWith(bytes, 'G', 'I', 'F', '8')) return "image/gif";
		if (startsWith(bytes, 'R', 'I', 'F', 'F') && bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
				&& bytes[11] == 'P') return "image/webp";
		if (startsWith(bytes, 'I', 'I', 0x2A, 0x00) || startsWith(bytes, 'M', 'M', 0x00, 0x2A)) return "image/tiff";
		if (startsWith(bytes, 'B', 'M') && bytes.length >= 26) return "image/bmp";
		String brand = ftypBrand(bytes);
		if (brand != null && HEIF_BRANDS.contains(brand)) return "image/heic";
		if (brand != null && (brand.equals("avif") || brand.equals("avis"))) return "image/avif";
		if (startsWith(bytes, 'P', 'K', 0x03, 0x04)) return ZIPPED.get(ext);
		if (startsWith(bytes, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) return OLE.get(ext);
		if (startsWith(bytes, '{', '\\', 'r', 't', 'f')) return "application/rtf";
		if (TEXT.containsKey(ext) && isText(bytes)) return TEXT.get(ext);
		return null;
	}

	private static String extension(String filename) {
		if (filename == null) return "";
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** The brand of an ISO media "ftyp" box (HEIC, AVIF), or null. */
	private static String ftypBrand(byte[] bytes) {
		if (bytes.length < 12 || bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p') return null;
		return new String(bytes, 8, 4, StandardCharsets.US_ASCII);
	}

	/** UTF-8 with no NUL byte: what a CSV, TXT, XML or EML is. */
	private static boolean isText(byte[] bytes) {
		for (byte b : bytes) if (b == 0) return false;
		try {
			StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes));
			return true;
		} catch (CharacterCodingException e) {
			return false;
		}
	}

	private static boolean startsWith(byte[] bytes, int... prefix) {
		if (bytes.length < prefix.length) return false;
		for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xFF) != prefix[i]) return false;
		return true;
	}
}
