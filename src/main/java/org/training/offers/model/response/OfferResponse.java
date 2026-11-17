package org.training.offers.model.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;

public record OfferResponse(
		UUID id,
		UUID userId,
		CurrencyCode sourceCurrency,
		CurrencyCode targetCurrency,
		BigDecimal amount,
		BigDecimal exchangeRate,
		BigDecimal targetAmount,
		OfferStatus status,
		Instant createdAt,
		Instant expiresAt,
		UUID acceptedBy,
		Instant acceptedAt) {
}
