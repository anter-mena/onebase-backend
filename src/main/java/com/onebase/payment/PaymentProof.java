package com.onebase.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A file proving a payment (table {@code payment_proofs}): an invoice, a screenshot,
 * a receipt email. The type is the one read from the bytes ({@link ProofRules#sniff}),
 * never the one the browser said.
 */
@Entity
@Table(name = "payment_proofs")
public class PaymentProof {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(nullable = false)
	private byte[] data;

	@Column(nullable = false)
	private String mime;

	@Column(nullable = false)
	private String filename;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected PaymentProof() {
	}

	PaymentProof(long paymentId, ProofRules.Checked file) {
		this.paymentId = paymentId;
		this.data = file.data();
		this.mime = file.mime();
		this.filename = file.filename();
		this.sizeBytes = file.data().length;
	}

	public Long getId() { return id; }
	public Long getPaymentId() { return paymentId; }
	public byte[] getData() { return data; }
	public String getMime() { return mime; }
	public String getFilename() { return filename; }
	public int getSizeBytes() { return sizeBytes; }
	public Instant getCreatedAt() { return createdAt; }
}
