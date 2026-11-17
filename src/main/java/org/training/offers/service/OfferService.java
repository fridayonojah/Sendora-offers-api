package org.training.offers.service;

import java.util.UUID;

import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.dto.OfferActionRequest;
import org.training.offers.model.dto.OfferFilterRequest;
import org.training.offers.model.response.OfferResponse;
import org.training.offers.model.response.PagedOfferResponse;

public interface OfferService {

	OfferResponse createOffer(String idempotencyKey, CreateOfferRequest request);

	PagedOfferResponse listOffers(OfferFilterRequest filter);

	OfferResponse getOffer(UUID id);

	OfferResponse acceptOffer(UUID id, OfferActionRequest request);

	OfferResponse cancelOffer(UUID id, OfferActionRequest request);
}
