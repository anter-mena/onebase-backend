package com.onebase.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One subscription plan (table {@code plans}): a number of devices for a number
 * of months, and its price in USD.
 *
 * <p>The 16 plans are created by the database migration and never added or
 * removed — only their price changes. {@code cost} and {@code credits} belong to
 * the Expenses tab and are filled in with it.
 */
@Entity
@Table(name = "plans")
public class Plan {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private short devices;

	@Column(nullable = false)
	private short months;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(precision = 12, scale = 2)
	private BigDecimal cost;

	private Integer credits;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Plan() {
	}

	void setPrice(BigDecimal price) {
		this.price = price;
		this.updatedAt = Instant.now();
	}

	public Long getId() { return id; }
	public int getDevices() { return devices; }
	public int getMonths() { return months; }
	public BigDecimal getPrice() { return price; }
	public BigDecimal getCost() { return cost; }
	public Integer getCredits() { return credits; }
	public Instant getUpdatedAt() { return updatedAt; }
}
