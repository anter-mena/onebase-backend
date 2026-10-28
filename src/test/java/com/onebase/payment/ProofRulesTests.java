package com.onebase.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** What a proof of payment can be: read from the bytes, the extension only between look-alikes. */
class ProofRulesTests {

	@Test
	void invoicesAndReceiptsAreRecognisedFromTheirBytes() {
		assertThat(ProofRules.sniff(bytes("%PDF-1.7 ..."), "invoice.pdf")).isEqualTo("application/pdf");
		// The bytes win over the name: a PDF called .png is a PDF.
		assertThat(ProofRules.sniff(bytes("%PDF-1.4"), "scan.png")).isEqualTo("application/pdf");
		assertThat(ProofRules.sniff(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0 }, "photo.jpg")).isEqualTo("image/jpeg");
		assertThat(ProofRules.sniff(bytes("RIFF\0\0\0\0WEBPVP8 "), "shot.webp")).isEqualTo("image/webp");
		assertThat(ProofRules.sniff(bytes("\0\0\0\u0018ftypheic\0\0\0\0"), "IMG_0001.HEIC")).isEqualTo("image/heic");
		assertThat(ProofRules.sniff(bytes("PK\u0003\u0004rest"), "Invoice.DOCX"))
			.isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		assertThat(ProofRules.sniff(bytes("PK\u0003\u0004rest"), "sheet.xlsx"))
			.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		assertThat(ProofRules.sniff(new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 },
			"receipt.msg")).isEqualTo("application/vnd.ms-outlook");
		assertThat(ProofRules.sniff(bytes("<?xml version=\"1.0\"?><Invoice/>"), "e-invoice.xml")).isEqualTo("application/xml");
		assertThat(ProofRules.sniff(bytes("date,amount\n2026-10-28,65.99\n"), "export.csv")).isEqualTo("text/csv");
		assertThat(ProofRules.sniff(bytes("From: pay@example.test\r\nSubject: Receipt\r\n\r\nPaid."), "receipt.eml")).isEqualTo("message/rfc822");
	}

	@Test
	void anythingElseIsRefused() {
		// Pages and drawings that can run script in a browser.
		assertThat(ProofRules.sniff(bytes("<html><script>alert(1)</script>"), "invoice.html")).isNull();
		assertThat(ProofRules.sniff(bytes("<svg onload=alert(1)>"), "logo.svg")).isNull();
		// A ZIP that is not an office file, an executable, binary text.
		assertThat(ProofRules.sniff(bytes("PK\u0003\u0004rest"), "archive.zip")).isNull();
		assertThat(ProofRules.sniff(bytes("MZ\u0090\0"), "invoice.exe")).isNull();
		assertThat(ProofRules.sniff(new byte[] { 'a', 0, 'b' }, "notes.txt")).isNull();
		assertThat(ProofRules.sniff(new byte[0], "empty.pdf")).isNull();
	}

	@Test
	void oneToFiveFilesTenMegabytesTogether() {
		MultipartFile pdf = new MockMultipartFile("proofs", "invoice.pdf", "application/pdf", bytes("%PDF-1.7"));
		List<ProofRules.Checked> checked = ProofRules.check(List.of(pdf));
		assertThat(checked).singleElement().satisfies(file -> {
			assertThat(file.mime()).isEqualTo("application/pdf");
			assertThat(file.filename()).isEqualTo("invoice.pdf");
		});

		assertThatThrownBy(() -> ProofRules.check(List.of())).hasMessageContaining("Add the proof of payment");
		assertThatThrownBy(() -> ProofRules.check(List.of(pdf, pdf, pdf, pdf, pdf, pdf))).hasMessageContaining("5 files at most");
		MultipartFile big = new MockMultipartFile("proofs", "big.pdf", "application/pdf", new byte[ProofRules.MAX_TOTAL_BYTES + 1]);
		assertThatThrownBy(() -> ProofRules.check(List.of(big))).hasMessageContaining("10 MB");
		MultipartFile page = new MockMultipartFile("proofs", "invoice.html", "text/html", bytes("<html>"));
		assertThatThrownBy(() -> ProofRules.check(List.of(pdf, page))).hasMessageContaining("\"invoice.html\" can't be used");
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}
}
