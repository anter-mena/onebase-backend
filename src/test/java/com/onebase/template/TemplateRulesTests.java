package com.onebase.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onebase.common.ApiException;
import com.onebase.template.TemplateBlock.ButtonType;
import com.onebase.template.TemplateBlock.Type;
import com.onebase.template.TemplateDtos.BlockRequest;
import com.onebase.template.TemplateDtos.TemplateRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The template rules without a database: what a template may hold, and which files are taken. */
class TemplateRulesTests {

	// ── A whole template ────────────────────────────────────────────────

	@Test
	void aGoodTemplateIsCleanedUp() {
		TemplateRules.Clean clean = TemplateRules.check(new TemplateRequest("  Trial ending ", "en", null, " EasyIPTV ", List.of(
			text("  Hi! Your trial ends soon. "),
			file("IMAGE", 7L),
			button("QUICK_REPLY", " Renew now ", "ignored"),
			button("url", "Open", "https://easyiptv.ca/renew"))));

		assertThat(clean.name()).isEqualTo("Trial ending");
		assertThat(clean.language()).isEqualTo("EN");
		assertThat(clean.active()).isTrue();
		assertThat(clean.footer()).isEqualTo("EasyIPTV");
		assertThat(clean.blocks()).extracting(TemplateBlock::getType)
			.containsExactly(Type.TEXT, Type.IMAGE, Type.BUTTON, Type.BUTTON);
		assertThat(clean.blocks().get(0).getText()).isEqualTo("Hi! Your trial ends soon.");
		assertThat(clean.blocks().get(2).getButtonLabel()).isEqualTo("Renew now");
		assertThat(clean.blocks().get(2).getButtonValue()).isNull(); // a quick reply has no value
		assertThat(clean.blocks().get(3).getButtonType()).isEqualTo(ButtonType.URL);
		assertThat(TemplateRules.summary(clean.blocks())).isEqualTo("1 text · 1 photo · 2 buttons");
	}

	@Test
	void nameLanguageAndFooterAreChecked() {
		refused(new TemplateRequest(" ", "EN", true, "", List.of(text("Hi"))), "Enter the template's name.");
		refused(new TemplateRequest("x".repeat(81), "EN", true, "", List.of(text("Hi"))), "Keep the name under 80 characters.");
		refused(new TemplateRequest("Hi", "DE", true, "", List.of(text("Hi"))), "Choose the language: English, French or Arabic.");
		refused(new TemplateRequest("Hi", "EN", true, "f".repeat(61), List.of(text("Hi"))), "Keep the footer under 60 characters.");
		assertThat(TemplateRules.check(new TemplateRequest("x".repeat(80), "AR", false, "f".repeat(60), List.of(text("Hi")))).active())
			.isFalse();
	}

	@Test
	void theMessageNeedsSomethingBesidesButtons() {
		refused(request(), "The message is empty: add a text or a file.");
		refused(request(text("Hi"), text("  ")), "Text 2 is empty: write it or remove it.");
		refused(request(button("QUICK_REPLY", "Yes", null)), "A button goes under a text or a file: the message can't start with one.");
		BlockRequest[] many = new BlockRequest[21];
		Arrays.fill(many, text("Hi"));
		refused(request(many), "A message can have 20 blocks at most.");
		refused(request(new BlockRequest("VIDEO", null, 1L, null, null, null)), "A block of the message has an unknown type: VIDEO.");
	}

	@Test
	void allTheTextsTogetherHold1024Characters() {
		TemplateRules.check(request(text("a".repeat(1000)), text("b".repeat(24))));
		refused(request(text("a".repeat(1000)), text("b".repeat(25))),
			"The texts together can be 1024 characters at most (now 1025).");
	}

	@Test
	void oneKindOfFileAndHowMany() {
		TemplateRules.check(request(file("IMAGE", 1L), file("IMAGE", 2L)));
		refused(request(file("IMAGE", 1L), file("IMAGE", 2L), file("IMAGE", 3L)), "2 photos per template at most.");
		refused(request(file("DOCUMENT", 1L), file("DOCUMENT", 2L)), "One PDF per template at most.");
		refused(request(file("AUDIO", 1L), file("AUDIO", 2L)), "One audio per template at most.");
		refused(request(file("IMAGE", 1L), file("DOCUMENT", 2L)), "One kind of file per template: photos, or a PDF, or an audio.");
		refused(request(file("IMAGE", 1L), file("IMAGE", 1L)), "The same file is used twice.");
		refused(request(file("IMAGE", null)), "The photo has no file: choose one or remove the block.");
	}

	@Test
	void buttons() {
		refused(request(text("Hi"), button("QUICK_REPLY", "A", null), button("QUICK_REPLY", "B", null), button("QUICK_REPLY", "C", null)),
			"2 buttons per template at most.");
		refused(request(text("Hi"), button("PHONE", "Call", "+1 514 555 0100"), button("PHONE", "Call 2", "+33 1 23 45 67 89")),
			"One phone button per template at most.");
		refused(request(text("Hi"), button("EMAIL", "Mail", "a@b.c")), "Choose the button's type: quick reply, link or phone.");
		refused(request(text("Hi"), button("QUICK_REPLY", " ", null)), "A button has no label: write one or remove the button.");
		refused(request(text("Hi"), button("QUICK_REPLY", "x".repeat(21), null)),
			"A button's label can be 20 characters at most: “" + "x".repeat(21) + "”.");
		refused(request(text("Hi"), button("URL", "Site", "easyiptv.ca")), "The button “Site” needs a full link, starting with https://");
		refused(request(text("Hi"), button("PHONE", "Call", "514 555 0100")),
			"The button “Call” needs a phone number starting with +, like +1 514 555 0100.");
		refused(request(text("Hi"), button("PHONE", "Call", "+")),
			"The button “Call” needs a phone number starting with +, like +1 514 555 0100.");

		TemplateRules.Clean clean = TemplateRules.check(request(text("Hi"), button("PHONE", "Call", " +1 514 555 0100 "),
			button("URL", "Site", "http://easyiptv.ca/a?b=c")));
		assertThat(clean.blocks().get(1).getButtonValue()).isEqualTo("+1 514 555 0100");
		assertThat(clean.blocks().get(2).getButtonValue()).isEqualTo("http://easyiptv.ca/a?b=c");
	}

	// ── Files ───────────────────────────────────────────────────────────

	@Test
	void theTypeIsReadFromTheBytes() {
		assertThat(TemplateRules.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
		assertThat(TemplateRules.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).isEqualTo("image/png");
		assertThat(TemplateRules.sniff("%PDF-1.7\n".getBytes())).isEqualTo("application/pdf");
		assertThat(TemplateRules.sniff("ID3\u0004".getBytes())).isEqualTo("audio/mpeg");
		assertThat(TemplateRules.sniff(bytes(0xFF, 0xFB, 0x90))).isEqualTo("audio/mpeg");
		assertThat(TemplateRules.sniff("OggS\0".getBytes())).isEqualTo("audio/ogg");
		assertThat(TemplateRules.sniff("#!AMR\n".getBytes())).isEqualTo("audio/amr");
		assertThat(TemplateRules.sniff(bytes(0xFF, 0xF1, 0x50))).isEqualTo("audio/aac");
		assertThat(TemplateRules.sniff(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '))).isEqualTo("audio/mp4");
		// A picture renamed .jpg is still what its bytes say; a page or a GIF is refused.
		assertThat(TemplateRules.sniff("<html><script>".getBytes())).isNull();
		assertThat(TemplateRules.sniff("GIF89a".getBytes())).isNull();
		assertThat(TemplateRules.sniff(new byte[0])).isNull();
	}

	@Test
	void uploadsAre4MbAtMost() {
		byte[] pdf = new byte[TemplateRules.MAX_FILE_BYTES];
		System.arraycopy("%PDF-".getBytes(), 0, pdf, 0, 5);
		assertThat(TemplateRules.checkUpload(pdf)).isEqualTo("application/pdf");
		byte[] tooBig = Arrays.copyOf(pdf, TemplateRules.MAX_FILE_BYTES + 1);
		assertThatThrownBy(() -> TemplateRules.checkUpload(tooBig)).hasMessage("Files can be 4 MB at most.");
		assertThatThrownBy(() -> TemplateRules.checkUpload("hello".getBytes()))
			.hasMessage("This file can't be sent: use a JPG or PNG picture, a PDF, or an MP3, OGG, AAC, M4A or AMR audio.");
	}

	@Test
	void aFileMustFitItsBlock() {
		TemplateRules.checkFile(Type.IMAGE, "image/png");
		TemplateRules.checkFile(Type.AUDIO, "audio/mp4");
		assertThatThrownBy(() -> TemplateRules.checkFile(Type.IMAGE, "application/pdf"))
			.hasMessage("This file can't be used as the photo: use a JPG or PNG picture.");
		assertThatThrownBy(() -> TemplateRules.checkFile(Type.DOCUMENT, "audio/ogg"))
			.hasMessage("This file can't be used as the PDF: use a PDF.");
	}

	@Test
	void fileNamesLoseTheirFolders() {
		assertThat(TemplateRules.fileName("C:\\Users\\me\\promo.jpg")).isEqualTo("promo.jpg");
		assertThat(TemplateRules.fileName("../../etc/\"x\r\n.pdf")).isEqualTo("x.pdf");
		assertThat(TemplateRules.fileName(null)).isEqualTo("file");
	}

	@Test
	void summaryCountsEachKind() {
		List<TemplateBlock> blocks = new ArrayList<>(List.of(TemplateBlock.text("a"), TemplateBlock.text("b"),
			TemplateBlock.file(Type.DOCUMENT, 1), TemplateBlock.button(ButtonType.PHONE, "Call", "+1")));
		assertThat(TemplateRules.summary(blocks)).isEqualTo("2 texts · 1 PDF · 1 button");
		assertThat(TemplateRules.summary(List.of(TemplateBlock.file(Type.AUDIO, 1)))).isEqualTo("1 audio");
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private static void refused(TemplateRequest request, String message) {
		assertThatThrownBy(() -> TemplateRules.check(request))
			.isInstanceOf(ApiException.class)
			.hasMessage(message)
			.satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	private static TemplateRequest request(BlockRequest... blocks) {
		return new TemplateRequest("Test", "EN", true, "", List.of(blocks));
	}

	private static BlockRequest text(String text) {
		return new BlockRequest("TEXT", text, null, null, null, null);
	}

	private static BlockRequest file(String type, Long id) {
		return new BlockRequest(type, null, id, null, null, null);
	}

	private static BlockRequest button(String type, String label, String value) {
		return new BlockRequest("BUTTON", null, null, type, label, value);
	}

	private static byte[] bytes(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
		return out;
	}
}
