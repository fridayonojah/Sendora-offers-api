package org.training.offers.model.dto;

import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;

public record OfferFilterRequest(
		CurrencyCode sourceCurrency,
		CurrencyCode targetCurrency,
		OfferStatus status,
		int page,
		int size,
		String sort) {
}
