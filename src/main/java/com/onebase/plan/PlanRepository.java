package com.onebase.plan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

	List<Plan> findAllByOrderByDevicesAscMonthsAsc();

	Optional<Plan> findByDevicesAndMonths(short devices, short months);
}
