package com.onebase.client;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClientRepository extends JpaRepository<Client, Long> {

	/** The Clients table: everyone not deleted, newest first. */
	List<Client> findByDeletedAtIsNullOrderByCreatedAtDescIdDesc();

	/** Deleted ones too: a number belongs to one client for good (see the V12 index). */
	Optional<Client> findByPhone(String phone);

	/** Clients on a status since before a moment (deleted ones left out): trials that ran out. */
	List<Client> findByStatusAndStatusChangedAtBeforeAndDeletedAtIsNull(Client.Status status, java.time.Instant before);

	/** How many clients each brand has (deleted ones left out): [brandId, count]. */
	@Query("select c.brandId, count(c) from Client c where c.deletedAt is null and c.brandId is not null group by c.brandId")
	List<Object[]> countByBrand();
}
