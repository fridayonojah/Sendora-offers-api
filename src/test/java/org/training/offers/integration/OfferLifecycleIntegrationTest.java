package org.training.offers.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.training.offers.model.OfferStatus;
import org.training.offers.model.entity.Offer;
import org.training.offers.repository.IdempotencyRecordRepository;
import org.training.offers.repository.OfferRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OfferLifecycleIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private OfferRepository offerRepository;
	@Autowired
	private IdempotencyRecordRepository idempotencyRepository;
	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		idempotencyRepository.deleteAll();
		offerRepository.deleteAll();
	}

	private String body(String source, String target, String amount, String rate, Instant expiresAt) {
		return """
				{"userId":"550e8400-e29b-41d4-a716-446655440000","sourceCurrency":"%s","targetCurrency":"%s",
				 "amount":%s,"exchangeRate":%s,"expiresAt":"%s"}""".formatted(source, target, amount, rate, expiresAt);
	}

	private Instant inFuture() {
		return Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
	}

	private JsonNode create(String key, String json) throws Exception {
		String response = mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	@Test
	void createsOpenOfferWithServerCalculatedTargetAmount() throws Exception {
		JsonNode created = create(UUID.randomUUID().toString(), body("CAD", "NGN", "1000.00", "1150.00", inFuture()));

		assertThat(created.get("status").asText()).isEqualTo("OPEN");
		assertThat(created.get("targetAmount").decimalValue()).isEqualByComparingTo("1150000");
		assertThat(offerRepository.count()).isEqualTo(1);
	}

	@Test
	void replayWithSameKeyAndPayloadDoesNotCreateDuplicate() throws Exception {
		String key = UUID.randomUUID().toString();
		String json = body("CAD", "NGN", "1000.00", "1150.00", inFuture());

		JsonNode first = create(key, json);
		JsonNode second = create(key, json);

		assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
		assertThat(offerRepository.count()).isEqualTo(1);
		assertThat(idempotencyRepository.count()).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentPayloadIsConflict() throws Exception {
		String key = UUID.randomUUID().toString();
		Instant expiry = inFuture();
		create(key, body("CAD", "NGN", "1000.00", "1150.00", expiry));

		mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(body("CAD", "NGN", "2000.00", "1150.00", expiry)))
				.andExpect(status().isConflict());
		assertThat(offerRepository.count()).isEqualTo(1);
	}

	@Test
	void rejectsInvalidCreateRequests() throws Exception {
		String[] bodies = {
				body("CAD", "NGN", "0", "1150", inFuture()),
				body("CAD", "NGN", "100", "-1", inFuture()),
				body("CAD", "CAD", "100", "1", inFuture()),
				body("CAD", "EUR", "100", "1", inFuture()),
				body("CAD", "NGN", "100", "1", Instant.now().minusSeconds(60)),
		};
		for (String json : bodies) {
			mockMvc.perform(post("/api/v1/offers").header("Idempotency-Key", UUID.randomUUID().toString())
							.contentType(MediaType.APPLICATION_JSON).content(json))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.traceId").isNotEmpty());
		}
		assertThat(offerRepository.count()).isZero();
	}

	@Test
	void missingIdempotencyKeyIsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/offers").contentType(MediaType.APPLICATION_JSON)
						.content(body("CAD", "NGN", "100", "1", inFuture())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void defaultListReturnsOnlyOpenUnexpiredOffers() throws Exception {
		create("k1", body("CAD", "NGN", "100", "1100", inFuture()));
		saveDirect(OfferStatus.MATCHED, inFuture());
		saveDirect(OfferStatus.OPEN, Instant.now().minusSeconds(60));

		mockMvc.perform(get("/api/v1/offers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].status").value("OPEN"));
	}

	@Test
	void explicitStatusFilterReturnsHistoricalOffers() throws Exception {
		saveDirect(OfferStatus.MATCHED, inFuture());
		saveDirect(OfferStatus.OPEN, Instant.now().minusSeconds(60));

		mockMvc.perform(get("/api/v1/offers").param("status", "MATCHED"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].status").value("MATCHED"));
		mockMvc.perform(get("/api/v1/offers").param("status", "EXPIRED"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].status").value("EXPIRED"));
	}

	@Test
	void filtersByCurrencyPair() throws Exception {
		create("k1", body("CAD", "NGN", "100", "1100", inFuture()));
		create("k2", body("USD", "NGN", "100", "1500", inFuture()));

		mockMvc.perform(get("/api/v1/offers").param("sourceCurrency", "CAD").param("targetCurrency", "NGN"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].sourceCurrency").value("CAD"));
	}

	@Test
	void paginatesAndSorts() throws Exception {
		create("k1", body("CAD", "NGN", "100", "1100", inFuture()));
		create("k2", body("CAD", "NGN", "100", "1300", inFuture()));
		create("k3", body("CAD", "NGN", "100", "1200", inFuture()));

		mockMvc.perform(get("/api/v1/offers").param("size", "2").param("sort", "exchangeRate,asc"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content[0].exchangeRate").value(1100.0))
				.andExpect(jsonPath("$.content[1].exchangeRate").value(1200.0));

		mockMvc.perform(get("/api/v1/offers").param("page", "1").param("size", "2").param("sort", "exchangeRate,desc"))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].exchangeRate").value(1100.0));
	}

	@Test
	void rejectsInvalidPagingAndSortParameters() throws Exception {
		mockMvc.perform(get("/api/v1/offers").param("size", "101")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/offers").param("page", "-1")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/offers").param("sort", "userId,asc")).andExpect(status().isBadRequest());
	}

	private static final String OWNER = "550e8400-e29b-41d4-a716-446655440000";
	private static final String BUYER = "550e8400-e29b-41d4-a716-446655440001";

	private String action(String userId) {
		return "{\"userId\":\"" + userId + "\"}";
	}

	private int act(String id, String verb, String userId) throws Exception {
		return mockMvc.perform(post("/api/v1/offers/" + id + "/" + verb).contentType(MediaType.APPLICATION_JSON)
				.content(action(userId))).andReturn().getResponse().getStatus();
	}

	private String createOpen() throws Exception {
		return create(UUID.randomUUID().toString(), body("CAD", "NGN", "100", "1100", inFuture())).get("id").asText();
	}

	@Test
	void retrievesOfferAndReturns404ForUnknown() throws Exception {
		String id = createOpen();

		mockMvc.perform(get("/api/v1/offers/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id));
		mockMvc.perform(get("/api/v1/offers/" + UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void elapsedOpenOfferIsReportedAsExpired() throws Exception {
		Offer offer = saveDirect(OfferStatus.OPEN, Instant.now().minusSeconds(60));

		mockMvc.perform(get("/api/v1/offers/" + offer.getId())).andExpect(jsonPath("$.status").value("EXPIRED"));
		assertThat(act(offer.getId().toString(), "accept", BUYER)).isEqualTo(409);
		assertThat(act(offer.getId().toString(), "cancel", offer.getUserId().toString())).isEqualTo(409);
	}

	@Test
	void otherUserAcceptsOpenOffer() throws Exception {
		String id = createOpen();

		mockMvc.perform(post("/api/v1/offers/" + id + "/accept").contentType(MediaType.APPLICATION_JSON)
						.content(action(BUYER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("MATCHED"))
				.andExpect(jsonPath("$.acceptedBy").value(BUYER))
				.andExpect(jsonPath("$.acceptedAt").isNotEmpty());
		assertThat(act(id, "accept", BUYER)).isEqualTo(409);
		assertThat(act(id, "cancel", OWNER)).isEqualTo(409);
	}

	@Test
	void ownerCannotAcceptOwnOffer() throws Exception {
		assertThat(act(createOpen(), "accept", OWNER)).isEqualTo(403);
	}

	@Test
	void acceptUnknownOfferIs404() throws Exception {
		assertThat(act(UUID.randomUUID().toString(), "accept", BUYER)).isEqualTo(404);
	}

	@Test
	void ownerCancelsOpenOfferAndItCannotBeAccepted() throws Exception {
		String id = createOpen();

		mockMvc.perform(post("/api/v1/offers/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
						.content(action(OWNER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(act(id, "accept", BUYER)).isEqualTo(409);
		assertThat(act(id, "cancel", OWNER)).isEqualTo(409);
	}

	@Test
	void nonOwnerCannotCancel() throws Exception {
		String id = createOpen();

		assertThat(act(id, "cancel", BUYER)).isEqualTo(403);
		mockMvc.perform(get("/api/v1/offers/" + id)).andExpect(jsonPath("$.status").value("OPEN"));
	}

	@Test
	void concurrentAcceptsYieldExactlyOneSuccess() throws Exception {
		String id = createOpen();
		String[] users = { BUYER, "550e8400-e29b-41d4-a716-446655440002" };
		ExecutorService pool = Executors.newFixedThreadPool(users.length);
		CountDownLatch ready = new CountDownLatch(users.length);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (String user : users) {
				results.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return act(id, "accept", user);
				}));
			}
			ready.await();
			go.countDown();

			List<Integer> codes = new ArrayList<>();
			for (Future<Integer> f : results) {
				codes.add(f.get(30, TimeUnit.SECONDS));
			}
			assertThat(codes).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void healthEndpointIsUp() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	private Offer saveDirect(OfferStatus status, Instant expiresAt) {
		return offerRepository.save(new Offer(UUID.randomUUID(), UUID.randomUUID(),
				org.training.offers.model.CurrencyCode.CAD, org.training.offers.model.CurrencyCode.NGN,
				new java.math.BigDecimal("100.0000"), new java.math.BigDecimal("1000.00000000"),
				new java.math.BigDecimal("100000.0000"), status, Instant.now().minusSeconds(3600), expiresAt));
	}
}
