package com.onebase.payment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Deleted payments are left out of every query here: they count for nothing. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** A client's payments, newest first. */
	List<Payment> findByClientIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long clientId);

	/** The payments of several clients, for the Clients table's totals. */
	List<Payment> findByClientIdInAndDeletedAtIsNull(Collection<Long> clientIds);

	/** When the client's paid time runs out, or null when they never paid. */
	@Query("select max(p.endsOn) from Payment p where p.clientId = :clientId and p.deletedAt is null")
	LocalDate latestEnd(@Param("clientId") Long clientId);

	/** Panel credit spent by every payment. */
	@Query("select coalesce(sum(p.creditsUsed), 0) from Payment p where p.deletedAt is null")
	long totalCreditsUsed();

	/** What each payment method has received: [methodId, amount]. */
	@Query("select p.paymentMethodId, sum(p.amount) from Payment p where p.deletedAt is null group by p.paymentMethodId")
	List<Object[]> totalsByMethod();

	/** Clients whose paid time has run out: the ids. */
	@Query("select p.clientId from Payment p where p.deletedAt is null group by p.clientId having max(p.endsOn) < :today")
	List<Long> clientsEndedBefore(@Param("today") LocalDate today);
}
