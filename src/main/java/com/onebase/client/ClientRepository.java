package com.onebase.client;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientRepository extends JpaRepository<Client, Long> {

	/** Clients added in [from, to), deleted ones left out: the weekly report's new clients. */
	List<Client> findByDeletedAtIsNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.Instant from, java.time.Instant to);

	/** How many clients are in a status (deleted ones left out): the Clients notification counts New. */
	long countByStatusAndDeletedAtIsNull(Client.Status status);

	/** The Clients table: everyone not deleted, newest first. */
	List<Client> findByDeletedAtIsNullOrderByCreatedAtDescIdDesc();

	/** Deleted ones too: a number belongs to one client for good (see the V12 index). */
	Optional<Client> findByPhone(String phone);

	/** Clients on a status since before a moment (deleted ones left out): trials that ran out. */
	List<Client> findByStatusAndStatusChangedAtBeforeAndDeletedAtIsNull(Client.Status status, java.time.Instant before);

	/** How many clients each brand has (deleted ones left out): [brandId, count]. */
	@Query("select c.brandId, count(c) from Client c where c.deletedAt is null and c.brandId is not null group by c.brandId")
	List<Object[]> countByBrand();

	/**
	 * Clients added in [from, to), by brand and quarter (1–4, in {@code zone}): [brandId, quarter, count].
	 * One row per client, so a client with ten plans counts once. Deleted ones left out.
	 */
	@Query(value = "SELECT brand_id, CAST(EXTRACT(QUARTER FROM created_at AT TIME ZONE :zone) AS integer), COUNT(*)"
		+ " FROM clients WHERE deleted_at IS NULL AND brand_id IS NOT NULL AND created_at >= :from AND created_at < :to"
		+ " GROUP BY 1, 2", nativeQuery = true)
	List<Object[]> countByBrandAndQuarter(@Param("zone") String zone, @Param("from") java.time.Instant from,
		@Param("to") java.time.Instant to);
}
