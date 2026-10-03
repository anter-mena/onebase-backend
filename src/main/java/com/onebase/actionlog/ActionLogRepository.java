package com.onebase.actionlog;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

	List<ActionLog> findAllByOrderByCreatedAtDescIdDesc(Pageable page);
}
