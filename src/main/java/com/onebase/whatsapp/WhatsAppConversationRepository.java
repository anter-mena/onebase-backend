package com.onebase.whatsapp;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WhatsAppConversationRepository extends JpaRepository<WhatsAppConversation, Long> {

	Optional<WhatsAppConversation> findByWaId(String waId);

	@Query("select c from WhatsAppConversation c order by c.lastMessageAt desc nulls last, c.id desc")
	List<WhatsAppConversation> findAllNewestFirst();

	List<WhatsAppConversation> findByClientIdIn(Collection<Long> clientIds);

	List<WhatsAppConversation> findByClientIdIsNull();

	@Query("select coalesce(sum(c.unreadCount), 0) from WhatsAppConversation c")
	long totalUnread();
}
