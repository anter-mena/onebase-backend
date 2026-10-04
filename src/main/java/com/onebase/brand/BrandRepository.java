package com.onebase.brand;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {

	/** Domains are stored lowercase and without "www.", so callers clean them first (BrandLinks.website). */
	Optional<Brand> findByDomain(String domain);

	List<Brand> findAllByOrderByCreatedAtAscIdAsc();
}
