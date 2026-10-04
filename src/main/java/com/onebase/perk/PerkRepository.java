package com.onebase.perk;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerkRepository extends JpaRepository<Perk, Long> {

	List<Perk> findAllByOrderByCreatedAtAscIdAsc();

	/** Names are unique without regard to capitals (see the V8 index). */
	Optional<Perk> findByNameIgnoreCase(String name);
}
