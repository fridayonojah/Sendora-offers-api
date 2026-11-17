package org.training.offers.service.implementation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.entity.IdempotencyRecord;
import org.training.offers.repository.IdempotencyRecordRepository;
import org.training.offers.service.IdempotencyService;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

	private final IdempotencyRecordRepository repository;
	private final Clock clock;

	public IdempotencyServiceImpl(IdempotencyRecordRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	public String computeRequestHash(CreateOfferRequest request) {
		// Numbers are normalised so 1000 and 1000.00 hash identically.
		String canonical = String.join("|",
				request.userId().toString(),
				request.sourceCurrency().name(),
				request.targetCurrency().name(),
				request.amount().stripTrailingZeros().toPlainString(),
				request.exchangeRate().stripTrailingZeros().toPlainString(),
				request.expiresAt().toString());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	@Override
	public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
		return repository.findByIdempotencyKey(idempotencyKey);
	}

	@Override
	public IdempotencyRecord save(String idempotencyKey, String requestHash, UUID offerId) {
		// Flush so a unique-key violation surfaces inside the caller's transaction.
		return repository.saveAndFlush(
				new IdempotencyRecord(UUID.randomUUID(), idempotencyKey, requestHash, offerId, Instant.now(clock)));
	}
}
