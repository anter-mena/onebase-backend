package com.onebase.whatsapp;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

	Optional<WhatsAppMessage> findByWaMessageId(String waMessageId);

	boolean existsByWaMessageId(String waMessageId);

	List<WhatsAppMessage> findByConversationIdOrderByCreatedAtAscIdAsc(long conversationId);

	Optional<WhatsAppMessage> findFirstByConversationIdAndDirectionOrderByCreatedAtDescIdDesc(long conversationId,
			WhatsAppMessage.Direction direction);
}
