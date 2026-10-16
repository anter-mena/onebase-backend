package com.onebase.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onebase.client.ClientService;
import com.onebase.template.TemplateService;
import com.onebase.whatsapp.WhatsAppMessage.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Keeping WhatsApp files without a database or Meta: the media endpoint, the sweeper's retries, Meta's "gone". */
class WhatsAppMediaUnitTests {

	private final WhatsAppMessageRepository messages = mock(WhatsAppMessageRepository.class);
	private final WhatsAppMediaRepository media = mock(WhatsAppMediaRepository.class);
	private final WhatsAppCloudApi api = mock(WhatsAppCloudApi.class);
	private final WhatsAppMediaStore store = new WhatsAppMediaStore(media, api);

	// ── The media endpoint ──────────────────────────────────────────────

	@Test
	void ourCopyIsServedWithoutAskingMeta() {
		WhatsAppMessage message = inbound(41L, "media-41", Instant.now());
		when(messages.findById(41L)).thenReturn(Optional.of(message));
		when(media.findById(41L)).thenReturn(Optional.of(new WhatsAppMedia(41L, new byte[] { 1, 2, 3 }, "audio/ogg")));

		WhatsAppCloudApi.MediaFile file = service().media(41L);

		assertThat(file.bytes()).containsExactly(1, 2, 3);
		assertThat(file.mime()).isEqualTo("audio/ogg");
		verify(api, never()).download(anyString());
	}

	@Test
	void withoutACopyItIsFetchedFromMetaAndKept() {
		WhatsAppMessage message = inbound(42L, "media-42", Instant.now());
		when(messages.findById(42L)).thenReturn(Optional.of(message));
		when(media.findById(42L)).thenReturn(Optional.empty());
		when(api.download("media-42")).thenReturn(new WhatsAppCloudApi.MediaFile(new byte[] { 9 }, "image/jpeg"));

		assertThat(service().media(42L).bytes()).containsExactly(9);
		verify(media).saveAndFlush(any(WhatsAppMedia.class));
	}

	@Test
	void whenMetaDeletedItTheAnswerIsAClear404() {
		WhatsAppMessage message = inbound(43L, "media-43", Instant.now());
		when(messages.findById(43L)).thenReturn(Optional.of(message));
		when(media.findById(43L)).thenReturn(Optional.empty());
		when(api.download("media-43")).thenThrow(new WhatsAppCloudApi.MediaGoneException());

		assertThatThrownBy(() -> service().media(43L))
			.hasMessage("This file is no longer available: WhatsApp keeps files only for a limited time.")
			.satisfies(e -> assertThat(((com.onebase.common.ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void metasObjectDoesNotExistIsRecognisedAsGone() {
		assertThat(WhatsAppCloudApi.gone(new WhatsAppCloudApi.MetaException(400, 100, 33, "x"))).isTrue();
		assertThat(WhatsAppCloudApi.gone(new WhatsAppCloudApi.MetaException(400, 100, 0,
			WhatsAppCloudApi.explain(100, "Unsupported get request. Object with ID '123' does not exist")))).isTrue();
		assertThat(WhatsAppCloudApi.gone(new WhatsAppCloudApi.MetaException(404, 0, 0, "x"))).isTrue();
		assertThat(WhatsAppCloudApi.gone(new WhatsAppCloudApi.MetaException(500, 1, 0, "x"))).isFalse();
		assertThat(WhatsAppCloudApi.gone(new WhatsAppCloudApi.MetaException(401, 190, 0, "x"))).isFalse();
	}

	// ── The sweeper ─────────────────────────────────────────────────────

	@Test
	void aFailedFetchIsRetriedLaterThenGivenUpAfterADay() {
		WhatsAppMediaSweeper sweeper = new WhatsAppMediaSweeper(messages, store, mock(WhatsAppSettings.class));
		when(api.download(anyString())).thenThrow(new RuntimeException("timeout"));
		Instant now = Instant.now();

		WhatsAppMessage fresh = inbound(51L, "media-51", now.minusSeconds(60));
		assertThat(sweeper.fetch(fresh, now)).isFalse();
		assertThat(fresh.getMediaFetchAttempts()).isEqualTo(1);
		assertThat(fresh.getMediaFetchNextAt()).isEqualTo(now.plus(Duration.ofMinutes(1)));

		WhatsAppMessage old = inbound(52L, "media-52", now.minus(Duration.ofHours(23).plusMinutes(30)));
		for (int i = 0; i < 6; i++) old.mediaFetchFailed(now); // already 6 failures: next wait is an hour
		assertThat(sweeper.fetch(old, now)).isFalse();
		assertThat(old.getMediaFetchAttempts()).isEqualTo(WhatsAppMessage.MEDIA_GAVE_UP);
		assertThat(old.getMediaFetchNextAt()).isNull();
	}

	@Test
	void aFileMetaNoLongerHasIsNotRetried() {
		WhatsAppMediaSweeper sweeper = new WhatsAppMediaSweeper(messages, store, mock(WhatsAppSettings.class));
		when(api.download("media-53")).thenThrow(new WhatsAppCloudApi.MediaGoneException());
		WhatsAppMessage message = inbound(53L, "media-53", Instant.now());

		assertThat(sweeper.fetch(message, Instant.now())).isFalse();
		assertThat(message.getMediaFetchAttempts()).isEqualTo(WhatsAppMessage.MEDIA_GAVE_UP);
		verify(messages).save(message);
	}

	@Test
	void aFetchedFileIsKept() {
		WhatsAppMediaSweeper sweeper = new WhatsAppMediaSweeper(messages, store, mock(WhatsAppSettings.class));
		when(api.download("media-54")).thenReturn(new WhatsAppCloudApi.MediaFile(new byte[] { 7, 7 }, "application/pdf"));

		assertThat(sweeper.fetch(inbound(54L, "media-54", Instant.now()), Instant.now())).isTrue();
		verify(media).saveAndFlush(any(WhatsAppMedia.class));
		verify(messages, never()).save(any());
	}

	@Test
	void waitsDoubleUpToAnHour() {
		assertThat(WhatsAppMediaSweeper.wait(0)).isEqualTo(Duration.ofMinutes(1));
		assertThat(WhatsAppMediaSweeper.wait(3)).isEqualTo(Duration.ofMinutes(8));
		assertThat(WhatsAppMediaSweeper.wait(6)).isEqualTo(Duration.ofHours(1));
		assertThat(WhatsAppMediaSweeper.wait(500)).isEqualTo(Duration.ofHours(1));
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private WhatsAppService service() {
		return new WhatsAppService(mock(WhatsAppConversationRepository.class), messages, api, mock(WhatsAppSettings.class),
			mock(ClientService.class), mock(TemplateService.class), store);
	}

	/** A received photo, with the id the database would have given it. */
	private static WhatsAppMessage inbound(long id, String mediaId, Instant at) {
		WhatsAppMessage message = WhatsAppMessage.incoming(1L, "wamid." + id, Type.IMAGE, null, mediaId, "image/jpeg", null, at);
		try {
			java.lang.reflect.Field field = WhatsAppMessage.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(message, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return message;
	}
}
