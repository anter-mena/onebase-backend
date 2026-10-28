package com.onebase.payment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentProofRepository extends JpaRepository<PaymentProof, Long> {

	/** What a proof is, without its bytes (for the payment lists). */
	interface Info {
		Long getId();
		Long getPaymentId();
		String getFilename();
		String getMime();
		int getSizeBytes();
	}

	List<Info> findByPaymentIdInOrderByIdAsc(Collection<Long> paymentIds);
}
