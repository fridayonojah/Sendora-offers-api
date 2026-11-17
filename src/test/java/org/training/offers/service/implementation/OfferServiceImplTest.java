package org.training.offers.service.implementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.training.offers.exception.ForbiddenOperationException;
import org.training.offers.exception.InvalidRequestException;
import org.training.offers.exception.OfferConflictException;
import org.training.offers.exception.OfferNotFoundException;
import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.dto.CreateOfferRequest;
import org.training.offers.model.dto.OfferActionRequest;
import org.training.offers.model.dto.OfferFilterRequest;
import org.training.offers.model.entity.IdempotencyRecord;
import org.training.offers.model.entity.Offer;
import org.training.offers.model.mapper.OfferMapper;
import org.training.offers.model.response.OfferResponse;
import org.training.offers.repository.OfferRepository;
import org.training.offers.service.IdempotencyService;

@ExtendWith(MockitoExtension.class)
class OfferServiceImplTest {

	private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
	private static final UUID USER = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

	@Mock
	private OfferRepository offerRepository;
	@Mock
	private IdempotencyService idempotencyService;
	@Mock
	private TransactionTemplate transactionTemplate;

	private OfferServiceImpl service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = new OfferServiceImpl(offerRepository, idempotencyService, new OfferMapper(clock),
				transactionTemplate, clock);
	}

	private CreateOfferRequest request(CurrencyCode source, CurrencyCode target, String amount, String rate,
			Instant expiresAt) {
		return new CreateOfferRequest(USER, source, target, new BigDecimal(amount), new BigDecimal(rate), expiresAt);
	}

	private CreateOfferRequest validRequest() {
		return request(CurrencyCode.CAD, CurrencyCode.NGN, "1000.00", "1150.00", NOW.plusSeconds(3600));
	}

	@SuppressWarnings("unchecked")
	private void runTransactionCallbacks() {
		when(transactionTemplate.execute(any())).thenAnswer(inv ->
				((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class)));
	}

	@Test
	void createsOpenOfferWithCalculatedTargetAmount() {
		CreateOfferRequest request = validRequest();
		when(idempotencyService.computeRequestHash(request)).thenReturn("hash");
		when(idempotencyService.findByKey("key-1")).thenReturn(Optional.empty());
		when(offerRepository.saveAndFlush(any(Offer.class))).thenAnswer(inv -> inv.getArgument(0));
		runTransactionCallbacks();

		OfferResponse response = service.createOffer("key-1", request);

		assertThat(response.status()).isEqualTo(OfferStatus.OPEN);
		assertThat(response.targetAmount()).isEqualByComparingTo("1150000");
		assertThat(response.createdAt()).isEqualTo(NOW);
		assertThat(response.id()).isNotNull();
		verify(idempotencyService).save(any(), any(), any());
	}

	@Test
	void replayWithSamePayloadReturnsOriginalOfferWithoutSaving() {
		CreateOfferRequest request = validRequest();
		UUID offerId = UUID.randomUUID();
		Offer original = new Offer(offerId, USER, CurrencyCode.CAD, CurrencyCode.NGN, new BigDecimal("1000.00"),
				new BigDecimal("1150.00"), new BigDecimal("1150000.0000"), OfferStatus.OPEN, NOW, NOW.plusSeconds(3600));
		when(idempotencyService.computeRequestHash(request)).thenReturn("hash");
		when(idempotencyService.findByKey("key-1"))
				.thenReturn(Optional.of(new IdempotencyRecord(UUID.randomUUID(), "key-1", "hash", offerId, NOW)));
		when(offerRepository.findById(offerId)).thenReturn(Optional.of(original));

		OfferResponse response = service.createOffer("key-1", request);

		assertThat(response.id()).isEqualTo(offerId);
		verify(offerRepository, never()).saveAndFlush(any());
	}

	@Test
	void reusingKeyWithDifferentPayloadIsConflict() {
		CreateOfferRequest request = validRequest();
		when(idempotencyService.computeRequestHash(request)).thenReturn("new-hash");
		when(idempotencyService.findByKey("key-1")).thenReturn(Optional.of(
				new IdempotencyRecord(UUID.randomUUID(), "key-1", "old-hash", UUID.randomUUID(), NOW)));

		assertThatThrownBy(() -> service.createOffer("key-1", request)).isInstanceOf(OfferConflictException.class);
	}

	@Test
	void rejectsSameSourceAndTargetCurrency() {
		CreateOfferRequest request = request(CurrencyCode.CAD, CurrencyCode.CAD, "10", "1", NOW.plusSeconds(60));
		when(idempotencyService.computeRequestHash(request)).thenReturn("hash");
		when(idempotencyService.findByKey("key-1")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createOffer("key-1", request)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsExpiryAtOrBeforeNow() {
		for (Instant expiry : new Instant[] { NOW, NOW.minusSeconds(1) }) {
			CreateOfferRequest request = request(CurrencyCode.CAD, CurrencyCode.NGN, "10", "1", expiry);
			when(idempotencyService.computeRequestHash(request)).thenReturn("hash");
			when(idempotencyService.findByKey("key-1")).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.createOffer("key-1", request))
					.isInstanceOf(InvalidRequestException.class);
		}
	}

	@Test
	void rejectsBlankIdempotencyKey() {
		assertThatThrownBy(() -> service.createOffer("  ", validRequest()))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void listRejectsUnknownSortProperty() {
		var filter = new OfferFilterRequest(null, null, null, 0, 20, "password,asc");
		assertThatThrownBy(() -> service.listOffers(filter)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void listRejectsOversizedPage() {
		var filter = new OfferFilterRequest(null, null, null, 0, 101, "createdAt,desc");
		assertThatThrownBy(() -> service.listOffers(filter)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void listRejectsNegativePageAndBadDirection() {
		assertThatThrownBy(() -> service.listOffers(new OfferFilterRequest(null, null, null, -1, 20, "createdAt,desc")))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.listOffers(new OfferFilterRequest(null, null, null, 0, 20, "createdAt,up")))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static final UUID OTHER = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

	private Offer offer(OfferStatus status, Instant expiresAt) {
		return new Offer(UUID.randomUUID(), USER, CurrencyCode.CAD, CurrencyCode.NGN, new BigDecimal("1000.00"),
				new BigDecimal("1150.00"), new BigDecimal("1150000.0000"), status, NOW.minusSeconds(60), expiresAt);
	}

	private Offer lockedOffer(OfferStatus status, Instant expiresAt) {
		Offer offer = offer(status, expiresAt);
		when(offerRepository.findByIdForUpdate(offer.getId())).thenReturn(Optional.of(offer));
		lenient().when(offerRepository.saveAndFlush(offer)).thenReturn(offer);
		return offer;
	}

	@Test
	void getOfferReturnsExistingAndTreatsElapsedOpenOfferAsExpired() {
		Offer open = offer(OfferStatus.OPEN, NOW.plusSeconds(60));
		Offer elapsed = offer(OfferStatus.OPEN, NOW.minusSeconds(1));
		when(offerRepository.findById(open.getId())).thenReturn(Optional.of(open));
		when(offerRepository.findById(elapsed.getId())).thenReturn(Optional.of(elapsed));

		assertThat(service.getOffer(open.getId()).status()).isEqualTo(OfferStatus.OPEN);
		assertThat(service.getOffer(elapsed.getId()).status()).isEqualTo(OfferStatus.EXPIRED);
	}

	@Test
	void getOfferThrowsNotFound() {
		UUID id = UUID.randomUUID();
		when(offerRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getOffer(id)).isInstanceOf(OfferNotFoundException.class);
	}

	@Test
	void otherUserCanAcceptOpenOffer() {
		Offer offer = lockedOffer(OfferStatus.OPEN, NOW.plusSeconds(60));

		OfferResponse response = service.acceptOffer(offer.getId(), new OfferActionRequest(OTHER));

		assertThat(response.status()).isEqualTo(OfferStatus.MATCHED);
		assertThat(response.acceptedBy()).isEqualTo(OTHER);
		assertThat(response.acceptedAt()).isEqualTo(NOW);
	}

	@Test
	void ownerCannotAcceptOwnOffer() {
		Offer offer = lockedOffer(OfferStatus.OPEN, NOW.plusSeconds(60));

		assertThatThrownBy(() -> service.acceptOffer(offer.getId(), new OfferActionRequest(USER)))
				.isInstanceOf(ForbiddenOperationException.class);
		assertThat(offer.getStatus()).isEqualTo(OfferStatus.OPEN);
	}

	@Test
	void acceptRejectsMatchedCancelledAndExpiredOffers() {
		for (Offer offer : new Offer[] { lockedOffer(OfferStatus.MATCHED, NOW.plusSeconds(60)),
				lockedOffer(OfferStatus.CANCELLED, NOW.plusSeconds(60)),
				lockedOffer(OfferStatus.OPEN, NOW.minusSeconds(1)),
				lockedOffer(OfferStatus.OPEN, NOW) }) {
			assertThatThrownBy(() -> service.acceptOffer(offer.getId(), new OfferActionRequest(OTHER)))
					.isInstanceOf(OfferConflictException.class);
		}
	}

	@Test
	void acceptUnknownOfferIsNotFound() {
		UUID id = UUID.randomUUID();
		when(offerRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.acceptOffer(id, new OfferActionRequest(OTHER)))
				.isInstanceOf(OfferNotFoundException.class);
	}

	@Test
	void ownerCanCancelOpenOffer() {
		Offer offer = lockedOffer(OfferStatus.OPEN, NOW.plusSeconds(60));

		OfferResponse response = service.cancelOffer(offer.getId(), new OfferActionRequest(USER));

		assertThat(response.status()).isEqualTo(OfferStatus.CANCELLED);
	}

	@Test
	void nonOwnerCannotCancel() {
		Offer offer = lockedOffer(OfferStatus.OPEN, NOW.plusSeconds(60));

		assertThatThrownBy(() -> service.cancelOffer(offer.getId(), new OfferActionRequest(OTHER)))
				.isInstanceOf(ForbiddenOperationException.class);
	}

	@Test
	void cancelRejectsMatchedCancelledAndExpiredOffers() {
		for (Offer offer : new Offer[] { lockedOffer(OfferStatus.MATCHED, NOW.plusSeconds(60)),
				lockedOffer(OfferStatus.CANCELLED, NOW.plusSeconds(60)),
				lockedOffer(OfferStatus.OPEN, NOW.minusSeconds(1)) }) {
			assertThatThrownBy(() -> service.cancelOffer(offer.getId(), new OfferActionRequest(USER)))
					.isInstanceOf(OfferConflictException.class);
		}
	}
}
