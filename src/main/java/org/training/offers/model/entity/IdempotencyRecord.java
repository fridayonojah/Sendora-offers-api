package org.training.offers.model.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "idempotency_records",
		uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_records_key", columnNames = "idempotency_key"))
public class IdempotencyRecord {

	@Id
	private UUID id;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Column(name = "offer_id", nullable = false)
	private UUID offerId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected IdempotencyRecord() {
	}

	public IdempotencyRecord(UUID id, String idempotencyKey, String requestHash, UUID offerId, Instant createdAt) {
		this.id = id;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.offerId = offerId;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public UUID getOfferId() {
		return offerId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
