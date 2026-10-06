package com.onebase.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** What a month's earnings are measured against (table {@code monthly_targets}). USD. */
@Entity
@Table(name = "monthly_targets")
public class MonthlyTarget {

	/** The month's first day. */
	@Id
	private LocalDate month;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal target;

	@Column(name = "updated_by")
	private Long updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected MonthlyTarget() {
	}

	MonthlyTarget(LocalDate month, BigDecimal target, Long updatedBy) {
		this.month = month;
		this.target = target;
		this.updatedBy = updatedBy;
	}

	void set(BigDecimal target, Long updatedBy) {
		this.target = target;
		this.updatedBy = updatedBy;
		this.updatedAt = Instant.now();
	}

	public LocalDate getMonth() { return month; }
	public BigDecimal getTarget() { return target; }
}
