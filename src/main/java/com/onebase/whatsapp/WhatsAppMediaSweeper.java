package com.onebase.whatsapp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Copies the files clients send us from Meta into {@code whatsapp_media}, every minute.
 *
 * <p>Not done in the webhook: Meta wants its answer at once. A sweeper rather than a
 * background task per message, so nothing is lost on a restart — it simply picks up
 * what has no copy yet.
 *
 * <p>A failed try is retried later (1, 2, 4 … minutes, then hourly). It gives up when
 * Meta no longer has the file, when the file is too large, or a day after the message
 * arrived; the Inbox then tries once more when someone opens the file.
 */
@Component
class WhatsAppMediaSweeper {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppMediaSweeper.class);
	static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
	static final Duration MAX_WAIT = Duration.ofHours(1);
	/** Per run: files can be large, so a few at a time. */
	static final int BATCH = 20;

	private final WhatsAppMessageRepository messages;
	private final WhatsAppMediaStore store;
	private final WhatsAppSettings settings;

	WhatsAppMediaSweeper(WhatsAppMessageRepository messages, WhatsAppMediaStore store, WhatsAppSettings settings) {
		this.messages = messages;
		this.store = store;
		this.settings = settings;
	}

	@Scheduled(fixedDelay = 60 * 1000, initialDelay = 60 * 1000)
	public void run() {
		if (!settings.connected()) return;
		Instant now = Instant.now();
		List<WhatsAppMessage> due = messages.findMediaToFetch(now.minus(GIVE_UP_AFTER), now,
			WhatsAppMessage.MEDIA_GAVE_UP, PageRequest.of(0, BATCH));
		int kept = 0;
		for (WhatsAppMessage message : due) {
			if (fetch(message, now)) kept++;
		}
		if (kept > 0) log.info("Kept {} WhatsApp file(s) received", kept);
	}

	/** One message: true when its file is now kept. */
	boolean fetch(WhatsAppMessage message, Instant now) {
		try {
			store.fetch(message);
			return true;
		} catch (WhatsAppCloudApi.MediaGoneException | WhatsAppCloudApi.MediaTooLargeException e) {
			log.warn("Gave up keeping the file of WhatsApp message id={}: {}", message.getId(), e.getMessage());
			message.mediaFetchFailed(null);
		} catch (RuntimeException e) {
			Instant next = now.plus(wait(message.getMediaFetchAttempts()));
			if (next.isAfter(message.getCreatedAt().plus(GIVE_UP_AFTER))) {
				log.warn("Gave up keeping the file of WhatsApp message id={} after a day of failures: {}", message.getId(),
					e.getMessage());
				next = null;
			} else {
				log.info("Could not keep the file of WhatsApp message id={} yet: {}", message.getId(), e.getMessage());
			}
			message.mediaFetchFailed(next);
		}
		messages.save(message);
		return false;
	}

	/** 1, 2, 4 … minutes after each failure, an hour at most. */
	static Duration wait(int attempts) {
		Duration wait = Duration.ofMinutes(1L << Math.min(attempts, 10));
		return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
	}
}
