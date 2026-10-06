package com.onebase.payment;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One payment (table {@code payments}). Every figure is a copy taken when it was
 * recorded — the plan's price, cost and panel credits, each perk's cost — so a
 * later change in Configuration never rewrites it. USD.
 */
@Entity
@Table(name = "payments")
public class Payment {

	public enum Kind { NEW_PLAN, RENEWAL }

	/** A perk the payment included: how many, and what each cost then. */
	@Embeddable
	public record PerkLine(
			@Column(name = "perk_id", nullable = false) Long perkId,
			@Column(nullable = false) String name,
			@Column(nullable = false) int quantity,
			@Column(name = "unit_cost", nullable = false, precision = 12, scale = 2) BigDecimal unitCost) {
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "client_id", nullable = false, updatable = false)
	private Long clientId;

	@Column(name = "brand_id", nullable = false, updatable = false)
	private Long brandId;

	@Column(name = "payment_method_id", nullable = false, updatable = false)
	private Long paymentMethodId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Kind kind;

	@Column(nullable = false, updatable = false)
	private short devices;

	@Column(nullable = false, updatable = false)
	private short months;

	@Column(name = "plan_price", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal planPrice;

	@Column(nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "plan_cost", updatable = false, precision = 12, scale = 2)
	private BigDecimal planCost;

	@Column(name = "perks_cost", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal perksCost;

	@Column(name = "credits_used", nullable = false, updatable = false)
	private int creditsUsed;

	@Column(name = "starts_on", nullable = false, updatable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on", nullable = false, updatable = false)
	private LocalDate endsOn;

	@Column(name = "created_by", updatable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "payment_perks", joinColumns = @JoinColumn(name = "payment_id"))
	private List<PerkLine> perks = new ArrayList<>();

	protected Payment() {
	}

	Payment(Long clientId, Long brandId, Long paymentMethodId, Kind kind, int devices, int months, BigDecimal planPrice,
			BigDecimal amount, BigDecimal planCost, List<PerkLine> perks, int creditsUsed, LocalDate startsOn,
			LocalDate endsOn, Long createdBy) {
		this.clientId = clientId;
		this.brandId = brandId;
		this.paymentMethodId = paymentMethodId;
		this.kind = kind;
		this.devices = (short) devices;
		this.months = (short) months;
		this.planPrice = planPrice;
		this.amount = amount;
		this.planCost = planCost;
		this.perks = new ArrayList<>(perks);
		this.perksCost = perks.stream()
			.map(line -> line.unitCost().multiply(BigDecimal.valueOf(line.quantity())))
			.reduce(BigDecimal.ZERO, BigDecimal::add)
			.setScale(2);
		this.creditsUsed = creditsUsed;
		this.startsOn = startsOn;
		this.endsOn = endsOn;
		this.createdBy = createdBy;
	}

	void delete() {
		deletedAt = Instant.now();
	}

	/** Plan cost + perks; null when the plan had no cost on file (the net can't be told). */
	public BigDecimal expense() {
		return planCost == null ? null : planCost.add(perksCost);
	}

	public Long getId() { return id; }
	public Long getClientId() { return clientId; }
	public Long getBrandId() { return brandId; }
	public Long getPaymentMethodId() { return paymentMethodId; }
	public Kind getKind() { return kind; }
	public int getDevices() { return devices; }
	public int getMonths() { return months; }
	public BigDecimal getPlanPrice() { return planPrice; }
	public BigDecimal getAmount() { return amount; }
	public BigDecimal getPlanCost() { return planCost; }
	public BigDecimal getPerksCost() { return perksCost; }
	public int getCreditsUsed() { return creditsUsed; }
	public LocalDate getStartsOn() { return startsOn; }
	public LocalDate getEndsOn() { return endsOn; }
	public Long getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
	public boolean isDeleted() { return deletedAt != null; }
	public List<PerkLine> getPerks() { return List.copyOf(perks); }
}
