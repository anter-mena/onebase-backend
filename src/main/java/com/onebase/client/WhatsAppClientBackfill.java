package com.onebase.client;

import com.onebase.whatsapp.WhatsAppConversation;
import com.onebase.whatsapp.WhatsAppConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * At start-up, every WhatsApp conversation without a client gets one — the numbers
 * that wrote before the Clients module existed, or whose client changed number.
 * Does nothing once they all have one.
 */
@Component
class WhatsAppClientBackfill implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppClientBackfill.class);

	private final WhatsAppConversationRepository conversations;
	private final ClientService clients;

	WhatsAppClientBackfill(WhatsAppConversationRepository conversations, ClientService clients) {
		this.conversations = conversations;
		this.clients = clients;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		int linked = 0;
		for (WhatsAppConversation conversation : conversations.findByClientIdIsNull()) {
			Long clientId = clients.fromWhatsApp(conversation.getWaId(), conversation.getContactName());
			if (clientId == null) continue;
			conversation.linkClient(clientId);
			conversations.save(conversation);
			linked++;
		}
		if (linked > 0) log.info("Linked {} WhatsApp conversation(s) to their client", linked);
	}
}
