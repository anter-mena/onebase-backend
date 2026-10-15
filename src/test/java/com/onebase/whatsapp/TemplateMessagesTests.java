package com.onebase.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.onebase.template.TemplateBlock;
import com.onebase.template.TemplateBlock.ButtonType;
import com.onebase.template.TemplateBlock.Type;
import com.onebase.whatsapp.TemplateMessages.Interactive;
import com.onebase.whatsapp.TemplateMessages.Link;
import com.onebase.whatsapp.TemplateMessages.Media;
import com.onebase.whatsapp.TemplateMessages.Outgoing;
import com.onebase.whatsapp.TemplateMessages.Text;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A saved template made into WhatsApp messages, without Meta. */
class TemplateMessagesTests {

	@Test
	void withoutButtonsEachBlockIsItsOwnMessageAndTheFooterEndsTheLastText() {
		List<Outgoing> out = TemplateMessages.build(List.of(text("Hello"), image(7), text("Your code: 1234")), "EasyIPTV");

		assertThat(out).containsExactly(new Text("Hello"), new Media(Type.IMAGE, 7, null), new Text("Your code: 1234\n\nEasyIPTV"));
	}

	@Test
	void withNoTextTheFooterIsTheCaptionOfTheLastPhoto() {
		assertThat(TemplateMessages.build(List.of(image(1), image(2)), "EasyIPTV"))
			.containsExactly(new Media(Type.IMAGE, 1, null), new Media(Type.IMAGE, 2, "EasyIPTV"));
		// An audio takes no caption: the footer goes alone, after it.
		assertThat(TemplateMessages.build(List.of(audio(3)), "EasyIPTV"))
			.containsExactly(new Media(Type.AUDIO, 3, null), new Text("EasyIPTV"));
	}

	@Test
	void quickRepliesUnderATextMakeItInteractive() {
		List<Outgoing> out = TemplateMessages.build(List.of(text("Trial ends soon"), reply("Renew now"), reply("Not now"),
			text("Thanks!")), "EasyIPTV");

		Interactive expected = new Interactive("Trial ends soon", null, List.of("Renew now", "Not now"), null, "EasyIPTV");
		assertThat(out).containsExactly(expected, new Text("Thanks!"));
		Map<String, Object> payload = expected.payload(null, null);
		assertThat(payload).containsEntry("type", "button")
			.containsEntry("body", Map.of("text", "Trial ends soon"))
			.containsEntry("footer", Map.of("text", "EasyIPTV"))
			.doesNotContainKey("header");
		assertThat(payload.get("action")).isEqualTo(Map.of("buttons", List.of(
			Map.of("type", "reply", "reply", Map.of("id", "reply_1", "title", "Renew now")),
			Map.of("type", "reply", "reply", Map.of("id", "reply_2", "title", "Not now")))));
		// The conversation keeps the words, the footer and the buttons the client saw.
		assertThat(expected.shown()).isEqualTo("Trial ends soon\n\nEasyIPTV\n[Renew now]\n[Not now]");
	}

	@Test
	void aLinkUnderAPhotoUsesThePhotoAsHeaderAndTheTextBeforeAsBody() {
		List<Outgoing> out = TemplateMessages.build(List.of(text("New offer"), image(5), link("See offer", "https://x.test/o")), "");

		Interactive expected = new Interactive("New offer", new Media(Type.IMAGE, 5, null), List.of(),
			new Link("See offer", "https://x.test/o"), "");
		assertThat(out).containsExactly(expected);
		Map<String, Object> payload = expected.payload("media-5", "ignored.jpg");
		assertThat(payload).containsEntry("type", "cta_url")
			.containsEntry("header", Map.of("type", "image", "image", Map.of("id", "media-5")))
			.containsEntry("action", Map.of("name", "cta_url", "parameters", Map.of("display_text", "See offer", "url", "https://x.test/o")))
			.doesNotContainKey("footer");
		assertThat(expected.shown()).isEqualTo("New offer\n[See offer]");
	}

	@Test
	void aPdfWithButtonsAndNoTextBeforeItSaysMoreInfo() {
		List<Outgoing> out = TemplateMessages.build(List.of(pdf(9), reply("Got it"), text("Bye")), "");

		Interactive expected = new Interactive("More info:", new Media(Type.DOCUMENT, 9, null), List.of("Got it"), null, "");
		assertThat(out).containsExactly(expected, new Text("Bye"));
		assertThat(expected.payload("media-9", "guide.pdf").get("header"))
			.isEqualTo(Map.of("type", "document", "document", Map.of("id", "media-9", "filename", "guide.pdf")));
	}

	@Test
	void buttonsUnderAnAudioGoOnTheLastText() {
		List<Outgoing> out = TemplateMessages.build(List.of(text("Listen:"), audio(4), reply("Call me")), "");

		assertThat(out).containsExactly(new Interactive("Listen:", null, List.of("Call me"), null, ""), new Media(Type.AUDIO, 4, null));
		// No text at all: a message of their own, after the audio.
		assertThat(TemplateMessages.build(List.of(audio(4), reply("Call me")), ""))
			.containsExactly(new Media(Type.AUDIO, 4, null), new Interactive("More info:", null, List.of("Call me"), null, ""));
	}

	@Test
	void quickRepliesWinAndTheLinkBecomesALine() {
		List<Outgoing> out = TemplateMessages.build(List.of(text("Renew?"), reply("Yes"), link("Pay online", "https://pay.test")), "");

		assertThat(out).containsExactly(new Interactive("Renew?\n\nPay online: https://pay.test", null, List.of("Yes"), null, ""));
	}

	@Test
	void aPhoneIsAlwaysALine() {
		// With a link: the link is the button, the phone a line of the body.
		assertThat(TemplateMessages.build(List.of(text("Questions?"), phone("Call us", "+1 514 555 0100"),
				link("Help", "https://help.test")), "Team"))
			.containsExactly(new Interactive("Questions?\n\nCall us: +1 514 555 0100", null, List.of(),
				new Link("Help", "https://help.test"), "Team"));
		// Alone: no interactive message, the line (then the footer) ends the last text.
		assertThat(TemplateMessages.build(List.of(text("Hi"), phone("Call us", "+1 514 555 0100"), text("Bye")), "Team"))
			.containsExactly(new Text("Hi"), new Text("Bye\n\nCall us: +1 514 555 0100\n\nTeam"));
	}

	@Test
	void longTextsAreCutToWhatWhatsAppTakes() {
		String body = "a".repeat(1100);
		Interactive message = (Interactive) TemplateMessages.build(List.of(text(body), reply("Ok")), "").getFirst();
		assertThat(message.body()).hasSize(TemplateMessages.MAX_BODY).endsWith("…");
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private static TemplateBlock text(String text) {
		return TemplateBlock.text(text);
	}

	private static TemplateBlock image(long id) {
		return TemplateBlock.file(Type.IMAGE, id);
	}

	private static TemplateBlock pdf(long id) {
		return TemplateBlock.file(Type.DOCUMENT, id);
	}

	private static TemplateBlock audio(long id) {
		return TemplateBlock.file(Type.AUDIO, id);
	}

	private static TemplateBlock reply(String label) {
		return TemplateBlock.button(ButtonType.QUICK_REPLY, label, null);
	}

	private static TemplateBlock link(String label, String url) {
		return TemplateBlock.button(ButtonType.URL, label, url);
	}

	private static TemplateBlock phone(String label, String number) {
		return TemplateBlock.button(ButtonType.PHONE, label, number);
	}
}
