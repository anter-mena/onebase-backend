package com.onebase.dashboard;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyTargetRepository extends JpaRepository<MonthlyTarget, LocalDate> {
}
