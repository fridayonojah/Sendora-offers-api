package org.training.offers.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.training.offers.exception.ForbiddenOperationException;
import org.training.offers.exception.GlobalExceptionHandler;
import org.training.offers.exception.InvalidRequestException;
import org.training.offers.exception.OfferConflictException;
import org.training.offers.exception.OfferNotFoundException;
import org.training.offers.model.CurrencyCode;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.dto.OfferFilterRequest;
import org.training.offers.model.response.OfferResponse;
import org.training.offers.model.response.PagedOfferResponse;
import org.training.offers.service.OfferService;

@WebMvcTest(OfferController.class)
@Import({ GlobalExceptionHandler.class, OfferControllerTest.ClockConfig.class })
class OfferControllerTest {

	@TestConfiguration
	static class ClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
		}
	}

	private static final String BODY = """
			{"userId":"550e8400-e29b-41d4-a716-446655440000","sourceCurrency":"CAD","targetCurrency":"NGN",
			 "amount":1000.00,"exchangeRate":1150.00,"expiresAt":"2026-12-01T18:00:00Z"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OfferService offerService;

	private OfferResponse sample() {
		return new OfferResponse(UUID.randomUUID(), UUID.randomUUID(), CurrencyCode.CAD, CurrencyCode.NGN,
				new BigDecimal("1000.00"), new BigDecimal("1150.00"), new BigDecimal("1150000.0000"),
				OfferStatus.OPEN, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-12-01T18:00:00Z"), null,
				null);
	}

	@Test
	void createReturns201WithLocation() throws Exception {
		OfferResponse response = sample();
		when(offerService.createOffer(eq("key-1"), any())).thenReturn(response);

		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/offers/" + response.id()))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.targetAmount").value(1150000.0));
	}

	@Test
	void createWithoutIdempotencyKeyReturns400() throws Exception {
		mockMvc.perform(post("/api/v1/offers").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.traceId").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/v1/offers"));
	}

	@Test
	void createRejectsNonPositiveAmountAndRate() throws Exception {
		String body = BODY.replace("\"amount\":1000.00", "\"amount\":0").replace("1150.00", "-1");
		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createRejectsUnsupportedCurrency() throws Exception {
		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON).content(BODY.replace("NGN", "EUR")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createRejectsMissingFields() throws Exception {
		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createMapsConflictTo409() throws Exception {
		when(offerService.createOffer(any(), any())).thenThrow(new OfferConflictException("different payload"));

		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("CONFLICT"));
	}

	@Test
	void listUsesDefaultsAndReturnsPage() throws Exception {
		when(offerService.listOffers(any())).thenReturn(new PagedOfferResponse(List.of(sample()), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/offers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));

		ArgumentCaptor<OfferFilterRequest> captor = ArgumentCaptor.forClass(OfferFilterRequest.class);
		verify(offerService).listOffers(captor.capture());
		org.assertj.core.api.Assertions.assertThat(captor.getValue())
				.isEqualTo(new OfferFilterRequest(null, null, null, 0, 20, "createdAt,desc"));
	}

	@Test
	void listPassesFiltersAndSort() throws Exception {
		when(offerService.listOffers(any())).thenReturn(new PagedOfferResponse(List.of(), 0, 10, 0, 0));

		mockMvc.perform(get("/api/v1/offers").param("sourceCurrency", "CAD").param("targetCurrency", "NGN")
						.param("status", "MATCHED").param("page", "2").param("size", "10")
						.param("sort", "exchangeRate,asc"))
				.andExpect(status().isOk());

		verify(offerService).listOffers(new OfferFilterRequest(CurrencyCode.CAD, CurrencyCode.NGN,
				OfferStatus.MATCHED, 2, 10, "exchangeRate,asc"));
	}

	@Test
	void listRejectsUnknownCurrencyAndStatus() throws Exception {
		mockMvc.perform(get("/api/v1/offers").param("sourceCurrency", "EUR")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/offers").param("status", "BOGUS")).andExpect(status().isBadRequest());
	}

	@Test
	void listMapsInvalidRequestTo400() throws Exception {
		when(offerService.listOffers(any())).thenThrow(new InvalidRequestException("bad sort"));

		mockMvc.perform(get("/api/v1/offers").param("sort", "nope"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("bad sort"));
	}

	private static final String ACTION_BODY = "{\"userId\":\"550e8400-e29b-41d4-a716-446655440001\"}";

	@Test
	void getReturnsOffer() throws Exception {
		OfferResponse response = sample();
		when(offerService.getOffer(response.id())).thenReturn(response);

		mockMvc.perform(get("/api/v1/offers/" + response.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(response.id().toString()));
	}

	@Test
	void getUnknownReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		when(offerService.getOffer(id)).thenThrow(new OfferNotFoundException(id));

		mockMvc.perform(get("/api/v1/offers/" + id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}

	@Test
	void getWithMalformedIdReturns400() throws Exception {
		mockMvc.perform(get("/api/v1/offers/not-a-uuid")).andExpect(status().isBadRequest());
	}

	@Test
	void acceptReturns200() throws Exception {
		OfferResponse response = sample();
		when(offerService.acceptOffer(eq(response.id()), any())).thenReturn(response);

		mockMvc.perform(post("/api/v1/offers/" + response.id() + "/accept")
						.contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
				.andExpect(status().isOk());
	}

	@Test
	void acceptMapsForbiddenAndConflict() throws Exception {
		UUID forbidden = UUID.randomUUID();
		UUID conflict = UUID.randomUUID();
		when(offerService.acceptOffer(eq(forbidden), any())).thenThrow(new ForbiddenOperationException("own offer"));
		when(offerService.acceptOffer(eq(conflict), any())).thenThrow(new OfferConflictException("matched"));

		mockMvc.perform(post("/api/v1/offers/" + forbidden + "/accept")
						.contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/offers/" + conflict + "/accept")
						.contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
				.andExpect(status().isConflict());
	}

	@Test
	void cancelReturns200AndRequiresUserId() throws Exception {
		OfferResponse response = sample();
		when(offerService.cancelOffer(eq(response.id()), any())).thenReturn(response);

		mockMvc.perform(post("/api/v1/offers/" + response.id() + "/cancel")
						.contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/offers/" + response.id() + "/cancel")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void cancelMapsForbiddenTo403() throws Exception {
		UUID id = UUID.randomUUID();
		when(offerService.cancelOffer(eq(id), any())).thenThrow(new ForbiddenOperationException("not owner"));

		mockMvc.perform(post("/api/v1/offers/" + id + "/cancel")
						.contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
				.andExpect(status().isForbidden());
	}
}
