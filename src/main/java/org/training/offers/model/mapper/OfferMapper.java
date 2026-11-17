package org.training.offers.model.mapper;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.entity.Offer;
import org.training.offers.model.response.OfferResponse;

@Component
public class OfferMapper {

	private final Clock clock;

	public OfferMapper(Clock clock) {
		this.clock = clock;
	}

	public OfferResponse toResponse(Offer offer) {
		return new OfferResponse(
				offer.getId(),
				offer.getUserId(),
				offer.getSourceCurrency(),
				offer.getTargetCurrency(),
				offer.getAmount(),
				offer.getExchangeRate(),
				offer.getTargetAmount(),
				effectiveStatus(offer),
				offer.getCreatedAt(),
				offer.getExpiresAt(),
				offer.getAcceptedBy(),
				offer.getAcceptedAt());
	}

	// An OPEN offer past its expiry is reported as EXPIRED without a write.
	private OfferStatus effectiveStatus(Offer offer) {
		if (offer.getStatus() == OfferStatus.OPEN && !offer.getExpiresAt().isAfter(clock.instant())) {
			return OfferStatus.EXPIRED;
		}
		return offer.getStatus();
	}
}
