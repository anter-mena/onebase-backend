package com.onebase.payment;

import com.onebase.client.Client;
import com.onebase.client.ClientRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Active clients whose paid time has run out become Inactive (decided
 * 2026-10-06) — at start-up and then every hour, so the Clients table and
 * Renewals never show a lapsed client as Active for long.
 */
@Component
class ClientLapseJob {

	private static final Logger log = LoggerFactory.getLogger(ClientLapseJob.class);

	private final PaymentRepository payments;
	private final ClientRepository clients;
	private final ZoneId zone;

	ClientLapseJob(PaymentRepository payments, ClientRepository clients, @Value("${onebase.timezone:America/Toronto}") String zone) {
		this.payments = payments;
		this.clients = clients;
		this.zone = ZoneId.of(zone);
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(cron = "0 5 * * * *")
	@Transactional
	public void run() {
		int lapsed = 0;
		for (Client client : clients.findAllById(payments.clientsEndedBefore(LocalDate.now(zone)))) {
			if (client.getStatus() != Client.Status.ACTIVE || client.isDeleted()) continue;
			client.lapsed();
			clients.save(client);
			lapsed++;
		}
		if (lapsed > 0) log.info("{} client(s) became Inactive: their paid time ran out", lapsed);
	}
}
