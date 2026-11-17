package org.training.offers.service;

import java.util.Optional;
import java.util.UUID;

import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.entity.IdempotencyRecord;

public interface IdempotencyService {

	String computeRequestHash(CreateOfferRequest request);

	Optional<IdempotencyRecord> findByKey(String idempotencyKey);

	IdempotencyRecord save(String idempotencyKey, String requestHash, UUID offerId);
}
