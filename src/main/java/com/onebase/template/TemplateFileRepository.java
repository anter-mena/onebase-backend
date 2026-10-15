package com.onebase.template;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateFileRepository extends JpaRepository<TemplateFile, Long> {

	/** What a file is, without its bytes (for lists and checks). */
	interface Info {
		Long getId();
		String getFilename();
		String getMime();
		int getSizeBytes();
	}

	List<Info> findInfoByIdIn(Collection<Long> ids);

	/** Whether an active template uses this file: the Inbox may show only those. */
	@Query(value = """
		SELECT EXISTS (SELECT 1 FROM message_template_blocks b JOIN message_templates t ON t.id = b.template_id
		               WHERE b.file_id = :id AND t.active)""", nativeQuery = true)
	boolean usedByActiveTemplate(@Param("id") long id);

	@Modifying
	@Query(value = """
		DELETE FROM message_template_files f
		WHERE f.id IN (:ids)
		  AND NOT EXISTS (SELECT 1 FROM message_template_blocks b WHERE b.file_id = f.id)""", nativeQuery = true)
	int deleteUnusedAmong(@Param("ids") Collection<Long> ids);

	@Modifying
	@Query(value = """
		DELETE FROM message_template_files f
		WHERE f.created_at < :before
		  AND NOT EXISTS (SELECT 1 FROM message_template_blocks b WHERE b.file_id = f.id)""", nativeQuery = true)
	int deleteUnusedOlderThan(@Param("before") Instant before);
}
