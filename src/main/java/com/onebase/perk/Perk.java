package com.onebase.perk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An extra that costs you money, such as IBO Player (table {@code perks}).
 * Only a cost: the client's plan price stays the same with or without it.
 * No delete — a perk is switched off instead.
 */
@Entity
@Table(name = "perks")
public class Perk {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private String description;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal cost;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Perk() {
	}

	Perk(String name, String description, BigDecimal cost, boolean active) {
		this.name = name;
		this.description = description;
		this.cost = cost;
		this.active = active;
	}

	void update(String name, String description, BigDecimal cost) {
		this.name = name;
		this.description = description;
		this.cost = cost;
		this.updatedAt = Instant.now();
	}

	void setActive(boolean active) {
		this.active = active;
		this.updatedAt = Instant.now();
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public BigDecimal getCost() { return cost; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
}
