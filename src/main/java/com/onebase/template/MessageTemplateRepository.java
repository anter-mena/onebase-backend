package com.onebase.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

	@EntityGraph(attributePaths = "blocks")
	List<MessageTemplate> findAllByOrderByUpdatedAtDescIdDesc();

	@EntityGraph(attributePaths = "blocks")
	List<MessageTemplate> findByActiveTrueOrderByUpdatedAtDescIdDesc();

	/** Names are unique per language without regard to capitals (see the V16 index). */
	Optional<MessageTemplate> findByNameIgnoreCaseAndLanguage(String name, String language);
}
