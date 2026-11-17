package org.training.offers.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.training.offers.model.CurrencyCode;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOfferRequest(
		@NotNull UUID userId,
		@NotNull CurrencyCode sourceCurrency,
		@NotNull CurrencyCode targetCurrency,
		@NotNull @Positive @Digits(integer = 9, fraction = 4) BigDecimal amount,
		@NotNull @Positive @Digits(integer = 9, fraction = 8) BigDecimal exchangeRate,
		@NotNull Instant expiresAt) {
}
