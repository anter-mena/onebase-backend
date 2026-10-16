package com.onebase.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CreditTopupRepository extends JpaRepository<CreditTopup, Long> {

	@Query("select coalesce(sum(t.credits), 0) from CreditTopup t")
	long totalCredits();

	@Query("select coalesce(sum(t.amount), 0) from CreditTopup t")
	BigDecimal totalAmount();

	Optional<CreditTopup> findFirstByOrderByCreatedAtDescIdDesc();

	/** Top-ups paid between two moments, newest first: money out for the Dashboard and the Ledger. */
	List<CreditTopup> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(Instant from, Instant to);
}
