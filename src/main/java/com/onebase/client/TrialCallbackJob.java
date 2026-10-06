package com.onebase.client;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A trial that started more than 24 hours ago becomes Callback (decided
 * 2026-10-06) — at start-up and then every five minutes, so it shows in
 * Renewals within minutes of the day running out.
 */
@Component
class TrialCallbackJob {

	private static final Logger log = LoggerFactory.getLogger(TrialCallbackJob.class);
	static final Duration TRIAL_LENGTH = Duration.ofHours(24);

	private final ClientRepository clients;

	TrialCallbackJob(ClientRepository clients) {
		this.clients = clients;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 60 * 1000)
	@Transactional
	public void run() {
		int moved = 0;
		for (Client client : clients.findByStatusAndStatusChangedAtBeforeAndDeletedAtIsNull(Client.Status.TRIAL,
				Instant.now().minus(TRIAL_LENGTH))) {
			client.trialEnded();
			clients.save(client);
			moved++;
		}
		if (moved > 0) log.info("{} trial(s) ran out: now Callback", moved);
	}
}
