package com.onebase.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Panel credit bought (table {@code credit_topups}): how many credits, and what
 * they cost in USD. Written once — never edited or deleted, like the money
 * history it is part of.
 */
@Entity
@Table(name = "credit_topups")
public class CreditTopup {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private int credits;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	private String note;

	@Column(name = "created_by")
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected CreditTopup() {
	}

	CreditTopup(int credits, BigDecimal amount, String note, Long createdBy) {
		this.credits = credits;
		this.amount = amount;
		this.note = note;
		this.createdBy = createdBy;
	}

	public Long getId() { return id; }
	public int getCredits() { return credits; }
	public BigDecimal getAmount() { return amount; }
	public String getNote() { return note; }
	public Instant getCreatedAt() { return createdAt; }
}
