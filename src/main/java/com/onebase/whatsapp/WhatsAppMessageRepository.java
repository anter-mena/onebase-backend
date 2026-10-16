package com.onebase.whatsapp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

	Optional<WhatsAppMessage> findByWaMessageId(String waMessageId);

	boolean existsByWaMessageId(String waMessageId);

	List<WhatsAppMessage> findByConversationIdOrderByCreatedAtAscIdAsc(long conversationId);

	Optional<WhatsAppMessage> findFirstByConversationIdAndDirectionOrderByCreatedAtDescIdDesc(long conversationId,
			WhatsAppMessage.Direction direction);

	/** Received files we have no copy of yet, due for a try (oldest first): what WhatsAppMediaSweeper fetches. */
	@Query("""
		SELECT m FROM WhatsAppMessage m
		WHERE m.direction = com.onebase.whatsapp.WhatsAppMessage.Direction.IN AND m.mediaId IS NOT NULL
		  AND m.createdAt > :since AND m.mediaFetchAttempts < :gaveUp
		  AND (m.mediaFetchNextAt IS NULL OR m.mediaFetchNextAt <= :now)
		  AND NOT EXISTS (SELECT 1 FROM WhatsAppMedia f WHERE f.messageId = m.id)
		ORDER BY m.id""")
	List<WhatsAppMessage> findMediaToFetch(@Param("since") Instant since, @Param("now") Instant now,
			@Param("gaveUp") short gaveUp, Pageable page);
}
