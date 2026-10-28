package com.onebase.notification;

import com.onebase.client.Client;
import com.onebase.client.ClientRepository;
import com.onebase.client.ClientService;
import com.onebase.whatsapp.WhatsAppService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is waiting in each area, for the sidebar's counts (2026-10-28). One call for
 * the areas kept in the database; the Email Inbox counts its unread mail itself, from
 * Gmail ({@code /api/inbox/overview}), so a slow Gmail never holds these back.
 *
 * <p>Each count is news someone should act on, not a standing list:
 * <ul>
 * <li><b>Clients</b>: still New — they wrote, nobody has handled them yet.</li>
 * <li><b>Renewals</b>: ending within 10 days, or due a callback (Pending and Inactive
 * are lists to work through, not news).</li>
 * <li><b>WhatsApp</b>: unread messages.</li>
 * </ul>
 */
@Service
public class NotificationService {

	public record Counts(long clients, long renewals, long whatsapp) {
	}

	private final ClientRepository clients;
	private final ClientService clientService;
	private final WhatsAppService whatsApp;

	public NotificationService(ClientRepository clients, ClientService clientService, WhatsAppService whatsApp) {
		this.clients = clients;
		this.clientService = clientService;
		this.whatsApp = whatsApp;
	}

	@Transactional(readOnly = true)
	public Counts counts() {
		long renewals = clientService.renewals().stream()
			.filter(row -> row.group().equals("ENDING_SOON") || row.group().equals("CALLBACK"))
			.count();
		return new Counts(clients.countByStatusAndDeletedAtIsNull(Client.Status.NEW), renewals, whatsApp.status().unread());
	}
}
