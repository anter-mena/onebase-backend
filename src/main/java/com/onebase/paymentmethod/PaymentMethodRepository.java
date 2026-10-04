package com.onebase.paymentmethod;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

	List<PaymentMethod> findAllByOrderByCreatedAtAscIdAsc();

	/** Names are unique without regard to capitals (see the V7 index). */
	Optional<PaymentMethod> findByNameIgnoreCase(String name);
}
