package org.training.offers.model.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "offers")
public class Offer {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_currency", nullable = false, length = 3)
	private CurrencyCode sourceCurrency;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_currency", nullable = false, length = 3)
	private CurrencyCode targetCurrency;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(name = "exchange_rate", nullable = false, precision = 19, scale = 8)
	private BigDecimal exchangeRate;

	@Column(name = "target_amount", nullable = false, precision = 19, scale = 4)
	private BigDecimal targetAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private OfferStatus status;

	@Version
	private Long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "accepted_by")
	private UUID acceptedBy;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	protected Offer() {
	}

	public Offer(UUID id, UUID userId, CurrencyCode sourceCurrency, CurrencyCode targetCurrency,
			BigDecimal amount, BigDecimal exchangeRate, BigDecimal targetAmount, OfferStatus status,
			Instant createdAt, Instant expiresAt) {
		this.id = id;
		this.userId = userId;
		this.sourceCurrency = sourceCurrency;
		this.targetCurrency = targetCurrency;
		this.amount = amount;
		this.exchangeRate = exchangeRate;
		this.targetAmount = targetAmount;
		this.status = status;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public void markMatched(UUID acceptingUserId, Instant at) {
		this.status = OfferStatus.MATCHED;
		this.acceptedBy = acceptingUserId;
		this.acceptedAt = at;
	}

	public void markCancelled() {
		this.status = OfferStatus.CANCELLED;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public CurrencyCode getSourceCurrency() {
		return sourceCurrency;
	}

	public CurrencyCode getTargetCurrency() {
		return targetCurrency;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getExchangeRate() {
		return exchangeRate;
	}

	public BigDecimal getTargetAmount() {
		return targetAmount;
	}

	public OfferStatus getStatus() {
		return status;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public UUID getAcceptedBy() {
		return acceptedBy;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}
}
