package com.onebase.paymentmethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An account you receive money on (table {@code payment_methods}). There is no
 * delete: a method is switched off, so old payments keep pointing at it.
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

	public enum Provider { PAYPAL, BINANCE, INTERAC, DEBIT_CARD }

	/** Only decides which logos are drawn on the card. */
	public enum CardNetwork { VISA, MASTERCARD, BOTH }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Provider provider;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String holder;

	@Enumerated(EnumType.STRING)
	@Column(name = "card_network", nullable = false)
	private CardNetwork cardNetwork = CardNetwork.BOTH;

	private String instructions;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected PaymentMethod() {
	}

	PaymentMethod(Provider provider, String name, String holder, CardNetwork cardNetwork, String instructions, boolean active) {
		this.provider = provider;
		this.name = name;
		this.holder = holder;
		this.cardNetwork = cardNetwork;
		this.instructions = instructions;
		this.active = active;
	}

	void update(Provider provider, String name, String holder, CardNetwork cardNetwork, String instructions) {
		this.provider = provider;
		this.name = name;
		this.holder = holder;
		this.cardNetwork = cardNetwork;
		this.instructions = instructions;
		this.updatedAt = Instant.now();
	}

	void setActive(boolean active) {
		this.active = active;
		this.updatedAt = Instant.now();
	}

	public Long getId() { return id; }
	public Provider getProvider() { return provider; }
	public String getName() { return name; }
	public String getHolder() { return holder; }
	public CardNetwork getCardNetwork() { return cardNetwork; }
	public String getInstructions() { return instructions; }
	public boolean isActive() { return active; }
	public Instant getCreatedAt() { return createdAt; }
}
