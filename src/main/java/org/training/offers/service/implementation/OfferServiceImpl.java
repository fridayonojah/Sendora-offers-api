package org.training.offers.service.implementation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.training.offers.exception.ForbiddenOperationException;
import org.training.offers.exception.InvalidRequestException;
import org.training.offers.exception.OfferConflictException;
import org.training.offers.exception.OfferNotFoundException;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.dto.OfferActionRequest;
import org.training.offers.model.dto.OfferFilterRequest;
import org.training.offers.model.entity.IdempotencyRecord;
import org.training.offers.model.entity.Offer;
import org.training.offers.model.mapper.OfferMapper;
import org.training.offers.model.response.OfferResponse;
import org.training.offers.model.response.PagedOfferResponse;
import org.training.offers.repository.OfferRepository;
import org.training.offers.service.IdempotencyService;
import org.training.offers.service.OfferService;

import jakarta.persistence.criteria.Predicate;

@Service
public class OfferServiceImpl implements OfferService {

	private static final Logger log = LoggerFactory.getLogger(OfferServiceImpl.class);

	static final int MAX_PAGE_SIZE = 100;
	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
	private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "expiresAt", "exchangeRate", "amount",
			"targetAmount");

	private final OfferRepository offerRepository;
	private final IdempotencyService idempotencyService;
	private final OfferMapper offerMapper;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public OfferServiceImpl(OfferRepository offerRepository, IdempotencyService idempotencyService,
			OfferMapper offerMapper, TransactionTemplate transactionTemplate, Clock clock) {
		this.offerRepository = offerRepository;
		this.idempotencyService = idempotencyService;
		this.offerMapper = offerMapper;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	@Override
	public OfferResponse createOffer(String idempotencyKey, CreateOfferRequest request) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = idempotencyService.computeRequestHash(request);

		var existing = idempotencyService.findByKey(idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.get(), requestHash);
		}

		validateBusinessRules(request);

		try {
			return transactionTemplate.execute(status -> persist(idempotencyKey, requestHash, request));
		}
		catch (DataIntegrityViolationException e) {
			// A concurrent request with the same key committed first.
			IdempotencyRecord winner = idempotencyService.findByKey(idempotencyKey).orElseThrow(() -> e);
			return replay(winner, requestHash);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public PagedOfferResponse listOffers(OfferFilterRequest filter) {
		Pageable pageable = toPageable(filter);
		Page<Offer> page = offerRepository.findAll(toSpecification(filter, clock.instant()), pageable);
		List<OfferResponse> content = page.getContent().stream().map(offerMapper::toResponse).toList();
		return new PagedOfferResponse(content, page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

	@Override
	@Transactional(readOnly = true)
	public OfferResponse getOffer(UUID id) {
		return offerRepository.findById(id).map(offerMapper::toResponse)
				.orElseThrow(() -> new OfferNotFoundException(id));
	}

	@Override
	@Transactional
	public OfferResponse acceptOffer(UUID id, OfferActionRequest request) {
		Offer offer = offerRepository.findByIdForUpdate(id).orElseThrow(() -> new OfferNotFoundException(id));
		if (offer.getUserId().equals(request.userId())) {
			throw new ForbiddenOperationException("Owners cannot accept their own offer");
		}
		requireAvailable(offer, "accepted");

		offer.markMatched(request.userId(), Instant.now(clock));
		Offer saved = offerRepository.saveAndFlush(offer);
		log.info("action=OFFER_ACCEPTED offerId={} userId={} ownerId={}", id, request.userId(), offer.getUserId());
		return offerMapper.toResponse(saved);
	}

	@Override
	@Transactional
	public OfferResponse cancelOffer(UUID id, OfferActionRequest request) {
		Offer offer = offerRepository.findByIdForUpdate(id).orElseThrow(() -> new OfferNotFoundException(id));
		if (!offer.getUserId().equals(request.userId())) {
			throw new ForbiddenOperationException("Only the owner can cancel an offer");
		}
		requireAvailable(offer, "cancelled");

		offer.markCancelled();
		Offer saved = offerRepository.saveAndFlush(offer);
		log.info("action=OFFER_CANCELLED offerId={} userId={}", id, request.userId());
		return offerMapper.toResponse(saved);
	}

	private void requireAvailable(Offer offer, String verb) {
		boolean expired = offer.getStatus() == OfferStatus.EXPIRED
				|| (offer.getStatus() == OfferStatus.OPEN && !offer.getExpiresAt().isAfter(clock.instant()));
		OfferStatus effective = expired ? OfferStatus.EXPIRED : offer.getStatus();
		if (effective != OfferStatus.OPEN) {
			throw new OfferConflictException("Offer cannot be " + verb + " because it is " + effective);
		}
	}

	private OfferResponse persist(String idempotencyKey, String requestHash, CreateOfferRequest request) {
		BigDecimal targetAmount = request.amount().multiply(request.exchangeRate()).setScale(4, RoundingMode.HALF_UP);
		Offer offer = new Offer(UUID.randomUUID(), request.userId(), request.sourceCurrency(),
				request.targetCurrency(), request.amount(), request.exchangeRate(), targetAmount, OfferStatus.OPEN,
				Instant.now(clock), request.expiresAt());
		Offer saved = offerRepository.saveAndFlush(offer);
		idempotencyService.save(idempotencyKey, requestHash, saved.getId());
		return offerMapper.toResponse(saved);
	}

	private OfferResponse replay(IdempotencyRecord record, String requestHash) {
		if (!record.getRequestHash().equals(requestHash)) {
			throw new OfferConflictException("Idempotency-Key has already been used with a different request payload");
		}
		Offer offer = offerRepository.findById(record.getOfferId())
				.orElseThrow(() -> new IllegalStateException("Offer missing for idempotency record"));
		return offerMapper.toResponse(offer);
	}

	private void validateIdempotencyKey(String key) {
		if (key == null || key.isBlank()) {
			throw new InvalidRequestException("Idempotency-Key header must not be blank");
		}
		if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new InvalidRequestException(
					"Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
	}

	private void validateBusinessRules(CreateOfferRequest request) {
		if (request.sourceCurrency() == request.targetCurrency()) {
			throw new InvalidRequestException("sourceCurrency and targetCurrency must be different");
		}
		if (!request.expiresAt().isAfter(clock.instant())) {
			throw new InvalidRequestException("expiresAt must be in the future");
		}
	}

	private Pageable toPageable(OfferFilterRequest filter) {
		if (filter.page() < 0) {
			throw new InvalidRequestException("page must be zero or greater");
		}
		if (filter.size() < 1 || filter.size() > MAX_PAGE_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		return PageRequest.of(filter.page(), filter.size(), parseSort(filter.sort()));
	}

	private Sort parseSort(String sort) {
		String[] parts = sort.split(",", -1);
		String field = parts[0].trim();
		if (parts.length > 2 || !SORTABLE_FIELDS.contains(field)) {
			throw new InvalidRequestException("sort must be '<field>[,asc|desc]' where field is one of " + SORTABLE_FIELDS);
		}
		String direction = parts.length == 2 ? parts[1].trim() : "asc";
		Sort.Direction dir = switch (direction.toLowerCase()) {
			case "asc" -> Sort.Direction.ASC;
			case "desc" -> Sort.Direction.DESC;
			default -> throw new InvalidRequestException("sort direction must be 'asc' or 'desc'");
		};
		// id tie-breaker keeps page boundaries stable.
		return Sort.by(new Sort.Order(dir, field), Sort.Order.asc("id"));
	}

	private Specification<Offer> toSpecification(OfferFilterRequest filter, Instant now) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (filter.sourceCurrency() != null) {
				predicates.add(cb.equal(root.get("sourceCurrency"), filter.sourceCurrency()));
			}
			if (filter.targetCurrency() != null) {
				predicates.add(cb.equal(root.get("targetCurrency"), filter.targetCurrency()));
			}

			OfferStatus status = filter.status();
			Predicate stored = cb.equal(root.get("status"), OfferStatus.OPEN);
			Predicate elapsed = cb.lessThanOrEqualTo(root.get("expiresAt"), now);
			if (status == null || status == OfferStatus.OPEN) {
				predicates.add(stored);
				predicates.add(cb.greaterThan(root.get("expiresAt"), now));
			}
			else if (status == OfferStatus.EXPIRED) {
				predicates.add(cb.or(cb.equal(root.get("status"), OfferStatus.EXPIRED), cb.and(stored, elapsed)));
			}
			else {
				predicates.add(cb.equal(root.get("status"), status));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
