# Sendora — P2P FX Offer Management Service

## 1. Project purpose

This project is a standalone REST API for managing peer-to-peer foreign-exchange (P2P FX) offers.

A user creates an offer stating:
- the currency they want to exchange (`sourceCurrency`);
- the currency they want to receive (`targetCurrency`);
- the amount they are offering;
- the exchange rate they are proposing; and
- when the offer expires.

Another user can discover an available offer and accept it. The offer owner can cancel their own offer while it is still available.

**This service manages offers and their lifecycle only.** It does not transfer money, execute currency conversion, hold wallets, settle payments, or authenticate users.

## 2. Assessment technology

The assessment specifies:
- Java 21
- Spring Boot 3.x
- PostgreSQL
- Spring Data JPA
- Flyway (Database Migrations)
- Spring Boot Actuator (Health & Observability)
- Maven or Gradle
- Docker and Docker Compose
- Testcontainers (for PostgreSQL Integration Tests)

The API base path is `/api/v1`.

## 3. Suggested project structure

The structure below follows the folder convention in the supplied reference screenshot, expanded to support Flyway migrations, idempotency tracking, structured logging, and integration tests.

```text
Sendora-Offer-Service/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/training/offers/
│   │   │       ├── configuration/
│   │   │       │   ├── OpenApiConfiguration.java
│   │   │       │   └── TimeConfiguration.java
│   │   │       │
│   │   │       ├── controller/
│   │   │       │   └── OfferController.java
│   │   │       │
│   │   │       ├── exception/
│   │   │       │   ├── ApiError.java
│   │   │       │   ├── GlobalExceptionHandler.java
│   │   │       │   ├── OfferNotFoundException.java
│   │   │       │   ├── OfferConflictException.java
│   │   │       │   ├── ForbiddenOperationException.java
│   │   │       │   └── InvalidRequestException.java
│   │   │       │
│   │   │       ├── model/
│   │   │       │   ├── dto/
│   │   │       │   │   ├── CreateOfferRequest.java
│   │   │       │   │   ├── OfferActionRequest.java
│   │   │       │   │   └── OfferFilterRequest.java
│   │   │       │   │
│   │   │       │   ├── entity/
│   │   │       │   │   ├── IdempotencyRecord.java
│   │   │       │   │   └── Offer.java
│   │   │       │   │
│   │   │       │   ├── mapper/
│   │   │       │   │   └── OfferMapper.java
│   │   │       │   │
│   │   │       │   ├── response/
│   │   │       │   │   ├── OfferResponse.java
│   │   │       │   │   └── PagedOfferResponse.java
│   │   │       │   │
│   │   │       │   ├── OfferStatus.java
│   │   │       │   └── CurrencyCode.java
│   │   │       │
│   │   │       ├── repository/
│   │   │       │   ├── IdempotencyRecordRepository.java
│   │   │       │   └── OfferRepository.java
│   │   │       │
│   │   │       ├── service/
│   │   │       │   ├── implementation/
│   │   │       │   │   ├── IdempotencyServiceImpl.java
│   │   │       │   │   └── OfferServiceImpl.java
│   │   │       │   ├── IdempotencyService.java
│   │   │       │   └── OfferService.java
│   │   │       │
│   │   │       └── OfferServiceApplication.java
│   │   │
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/
│   │       │       ├── V1__create_offers_table.sql
│   │       │       └── V2__create_idempotency_records_table.sql
│   │       ├── application.yml
│   │       └── logback-spring.xml
│   │
│   └── test/
│       └── java/org/training/offers/
│           ├── controller/
│           │   └── OfferControllerTest.java
│           ├── integration/
│           │   └── OfferLifecycleIntegrationTest.java
│           └── service/implementation/
│               └── OfferServiceImplTest.java
│
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── .gitignore
└── README.md
```

### What each package is responsible for

| Package | Responsibility in this assessment |
|---|---|
| `configuration` | Shared application configuration, including a UTC `Clock` for deterministic expiry checks and OpenAPI documentation setup. |
| `controller` | Receives HTTP requests, extracts headers (`Idempotency-Key`) and pagination parameters, validates request bodies, calls the service, and returns HTTP responses. |
| `exception` | Defines meaningful business exceptions and converts them into consistent HTTP error responses in one place. |
| `model/dto` | Defines the data accepted by the API, such as create-offer, accept/cancel requests, and filter parameters. |
| `model/entity` | Defines the JPA `Offer` and `IdempotencyRecord` entities persisted in PostgreSQL. |
| `model/mapper` | Converts persisted entities into API response models. |
| `model/response` | Defines the shape of single and paginated responses returned to API clients. |
| `repository` | Reads and writes offers and idempotency records using Spring Data JPA with database-level locking. |
| `service` | Declares the offer and idempotency operations supported by the application. |
| `service/implementation` | Implements offer creation, idempotency deduplication, discovery, concurrent acceptance protection, cancellation, and expiry rules. |
| `resources` | Holds Spring configuration (`application.yml`), Flyway SQL migrations (`db/migration`), and structured JSON logging config (`logback-spring.xml`). |
| `test` | Contains unit tests and full-stack PostgreSQL integration tests (via Testcontainers) covering API behaviour, business rules, and concurrency. |

## 4. Core domain model

### Offer

An `Offer` represents one user's proposal to exchange one amount of one currency for another currency at a specified rate.

| Field | Meaning |
|---|---|
| `id` | Unique offer identifier generated by the service (UUID). |
| `userId` | UUID of the user who created the offer. |
| `sourceCurrency` | Currency the owner is offering. |
| `targetCurrency` | Currency the owner wants in return. |
| `amount` | Amount of source currency being offered. |
| `exchangeRate` | Proposed target-currency units for one source-currency unit. |
| `targetAmount` | Calculated by the server: `amount × exchangeRate`. |
| `status` | Current lifecycle state of the offer. |
| `version` | Optimistic lock version column (`@Version`) for concurrent update safety. |
| `createdAt` | Timestamp generated by the service. |
| `expiresAt` | Timestamp after which the offer is no longer available. |
| `acceptedBy` | UUID of the user who accepted the offer; populated on acceptance. |
| `acceptedAt` | Timestamp when the offer was accepted; populated on acceptance. |

Use `BigDecimal` for `amount`, `exchangeRate` and `targetAmount`. Do not use `double` or `float` for monetary calculations.

### Supported currencies

Only `CAD`, `USD`, `GBP` and `NGN` are supported. Source and target currencies must be different.

### Offer status

| Status | Meaning |
|---|---|
| `OPEN` | Offer is available for another user to accept. |
| `MATCHED` | Another user accepted the offer. |
| `CANCELLED` | The owner cancelled the offer. |
| `EXPIRED` | The expiry time has been reached. |

## 5. Use-case workflows

### Use case A — Create an offer (with Idempotency)

**Actor:** Offer owner.

1. Client sends `POST /api/v1/offers` with a required `Idempotency-Key` header (UUID or unique client string) and JSON body containing the owner's UUID, source and target currencies, amount, exchange rate, and expiry timestamp.
2. Service checks if the `Idempotency-Key` has already been processed:
   - If the key exists with an **identical request payload**, the service returns the previously created offer immediately without creating a duplicate.
   - If the key exists with a **different request payload**, the API rejects the request with `409 Conflict` (or `422 Unprocessable Entity`).
3. API validates required fields, positive amount and rate, supported currencies, different source/target currencies, and a future expiry timestamp.
4. Service calculates `targetAmount = amount × exchangeRate` using `BigDecimal` arithmetic.
5. Service generates the offer UUID and creation timestamp, saves the offer with status `OPEN`, and stores the idempotency record atomically in the same database transaction.
6. API returns the created offer with HTTP `201 Created`.

The client must not supply the generated offer ID, creation timestamp, target amount, or initial status.

### Use case B — Discover offers (with Pagination & Sorting)

**Actor:** A user looking for an offer to accept.

1. Client sends `GET /api/v1/offers`.
2. Optional query filters can specify `sourceCurrency`, `targetCurrency`, and `status`.
3. Optional pagination and sorting query parameters are supported:
   - `page` (zero-based page index, default `0`)
   - `size` (page size, default `20`, max `100`)
   - `sort` (e.g., `createdAt,desc`, `exchangeRate,asc`, `amount,desc`; default `createdAt,desc`)
4. If no status filter is supplied, the service returns only `OPEN` offers whose expiry time is still in the future.
5. If an explicit status is supplied, historical offers with that status can be retrieved.
6. API returns a paginated response (`content`, `page`, `size`, `totalElements`, `totalPages`).

Example:
`GET /api/v1/offers?sourceCurrency=CAD&targetCurrency=NGN&page=0&size=10&sort=exchangeRate,asc`

### Use case C — Retrieve one offer

**Actor:** API client.

1. Client sends `GET /api/v1/offers/{id}`.
2. Service looks up the offer by UUID.
3. If an `OPEN` offer has reached its expiry time, it is treated as `EXPIRED`.
4. If no offer exists, API returns `404 Not Found`.
5. Otherwise, API returns the offer.

### Use case D — Accept an offer (Concurrency Protected)

**Actor:** A user other than the offer owner.

1. Client sends `POST /api/v1/offers/{id}/accept` with the accepting user's UUID in the request body.
2. Inside a database transaction, the service acquires a **pessimistic write lock** (`SELECT ... FOR UPDATE`) on the offer row to prevent concurrent acceptance races.
3. Service checks the offer's current lifecycle state and expiry.
4. Only an `OPEN`, unexpired offer can be accepted.
5. The owner cannot accept their own offer.
6. On success, the service records `acceptedBy` and `acceptedAt`, changes status to `MATCHED`, commits the transaction, and emits a structured audit log event.
7. API returns the updated offer with HTTP `200 OK`.

A matched, cancelled, or expired offer cannot be accepted. Matching is full; partial acceptance is not supported.

### Use case E — Cancel an offer

**Actor:** Offer owner.

1. Client sends `POST /api/v1/offers/{id}/cancel` with the owner's UUID in the request body.
2. Service acquires a write lock on the offer and checks its state and expiry.
3. Only the owner can cancel an `OPEN`, unexpired offer.
4. On success, status changes to `CANCELLED` and a structured audit log event is recorded.
5. API returns the updated offer with HTTP `200 OK`.

A different user cannot cancel the offer. Matched, cancelled, or expired offers cannot be cancelled.

### Offer lifecycle

```text
                 Accept by another user
          ┌────────────────────────────────┐
          │                                ▼
       [OPEN] ─────────────────────────> [MATCHED]
          │
          ├──── Owner cancels ─────────> [CANCELLED]
          │
          └──── Expiry time reached ──> [EXPIRED]
```

`MATCHED`, `CANCELLED` and `EXPIRED` are terminal states for this assessment.

## 6. API contract

All endpoints are relative to `/api/v1` (except Spring Boot Actuator endpoints under `/actuator`).

| Method | Endpoint | Purpose | Success response |
|---|---|---|---|
| `POST` | `/offers` | Create an offer (supports `Idempotency-Key` header) | `201 Created` |
| `GET` | `/offers` | List/filter offers with pagination & sorting | `200 OK` |
| `GET` | `/offers/{id}` | Retrieve one offer | `200 OK` |
| `POST` | `/offers/{id}/accept` | Accept an offer | `200 OK` |
| `POST` | `/offers/{id}/cancel` | Cancel an offer | `200 OK` |
| `GET` | `/actuator/health` | Application & PostgreSQL health check | `200 OK` |

### Create request example

**Headers:**
```http
Content-Type: application/json
Idempotency-Key: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d
```

**Body:**
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceCurrency": "CAD",
  "targetCurrency": "NGN",
  "amount": 1000.00,
  "exchangeRate": 1150.00,
  "expiresAt": "2026-12-01T18:00:00Z"
}
```

Expected calculation:
- Source amount: `1000 CAD`
- Rate: `1 CAD = 1150 NGN`
- Target amount calculated by server: `1,150,000 NGN`

### Accept request example

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440001"
}
```

### Cancel request example

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

The cancel request must contain the offer owner's UUID.

## 7. Error response requirements

Return consistent JSON error responses including timestamp, status, error code, message, path, and correlation/trace ID.

| HTTP status | When to use |
|---|---|
| `400 Bad Request` | Invalid input, missing required fields, missing/malformed `Idempotency-Key`, unsupported currency, non-positive amount/rate, same currencies, invalid sort property, or invalid expiry. |
| `403 Forbidden` | User attempts an operation they are not allowed to perform, such as cancelling another user's offer or accepting their own offer. |
| `404 Not Found` | Offer UUID does not exist. |
| `409 Conflict` | Offer is no longer available (already matched, cancelled, or expired), concurrent modification conflict, or `Idempotency-Key` reused with a different request payload. |

The assessment does not require authentication. For accept and cancel operations, the acting user's UUID is supplied in the request body.

## 8. Persistence, observability & concurrency design

### Persistence & Migrations
- **PostgreSQL** stores offers and idempotency records so they survive application restarts.
- **Flyway** manages all schema changes via versioned SQL scripts in `src/main/resources/db/migration/` (`spring.jpa.hibernate.ddl-auto=validate`).
- **`BigDecimal`** is used for all monetary values (`NUMERIC(19, 4)` / `NUMERIC(19, 8)` in PostgreSQL).
- **Timestamps** are stored and serialized consistently in UTC (`Instant` / `TIMESTAMPTZ`).
- **Lazy Expiry Evaluation:** Expired `OPEN` offers are treated as `EXPIRED` when read or validated for an action. A scheduled expiry job is optional.

### Structured Logging & Health Checks
- **Structured Logging:** Configured via `logback-spring.xml` (using `logstash-logback-encoder`) to output JSON logs with `timestamp`, `level`, `logger`, `traceId`/`requestId`, `offerId`, `userId`, and `action`.
- **Actuator Health Endpoint:** Exposed at `/actuator/health` (`management.endpoint.health.show-details=when_authorized` or `always` for local assessment) to verify application liveness and PostgreSQL database connectivity.

### Concurrency Protection & Known Limitations
- **How Concurrent Acceptance is Protected:**
  - `POST /api/v1/offers/{id}/accept` and `POST /api/v1/offers/{id}/cancel` acquire a **pessimistic row-level write lock** (`@Lock(LockModeType.PESSIMISTIC_WRITE)` -> `SELECT ... FOR UPDATE`) on the target `offers` record within a `@Transactional` boundary.
  - When two users attempt to accept the same `OPEN` offer simultaneously, PostgreSQL serializes access to the row: the first transaction transitions the status to `MATCHED` and commits, while the second transaction wakes up, reads `status = MATCHED`, and fails cleanly with `409 Conflict`.
  - `POST /api/v1/offers` enforces idempotency using a unique database constraint on `idempotency_key` so concurrent duplicate submissions cannot create two offers.
- **Known Concurrency Limitations:**
  1. **Database Lock Contention on Hot Offers:** Pessimistic locking holds a row lock until the database transaction commits. Under extreme contention for a single popular offer, waiting threads block on database connections and may experience higher latency or lock wait timeouts (`PessimisticLockingFailureException`, mapped to `409 Conflict`).
  2. **In-Flight Idempotency Race Window:** If two identical `POST /offers` requests with the exact same `Idempotency-Key` arrive within the same millisecond before the first transaction commits, the second request triggers a database unique-constraint violation (`DataIntegrityViolationException`), which the service catches to return `409 Conflict` (or retries reading the committed idempotency record) rather than silently deduplicating before commit.
  3. **No Cross-Offer User Balance/Exposure Locking:** Because wallets and balances are out of scope, the service does not lock at the `userId` level. A single user can concurrently create or accept multiple separate offers without balance contention checks.
  4. **Read Snapshot Consistency (`GET /offers`):** Listing endpoints run without write locks (`READ_COMMITTED` isolation). An offer returned as `OPEN` in a list query may be matched or cancelled milliseconds later before the client submits `/accept`.

## 9. Tests to cover

Both **unit tests** (JUnit 5 + Mockito) and **integration tests** (Spring Boot Test + Testcontainers with real PostgreSQL) are mandatory and must cover the following scenarios:

**Create & Idempotency**
- Valid offer is saved as `OPEN` via Flyway-migrated PostgreSQL schema.
- Target amount is calculated accurately by the service using `BigDecimal`.
- Replaying a request with the same `Idempotency-Key` and identical payload returns the original `201 Created` offer without creating a duplicate row.
- Reusing an `Idempotency-Key` with a different payload returns `409 Conflict`.
- Reject zero/negative amount or exchange rate (`400`).
- Reject same source and target currencies (`400`).
- Reject unsupported currency values (`400`).
- Reject expiry in the past or at the current time (`400`).

**Retrieve, List, Pagination & Sorting**
- Existing offer can be retrieved (`200`).
- Unknown offer returns `404`.
- Default list excludes non-open and expired offers.
- Currency and explicit status filters work.
- Pagination (`page`, `size`) and sorting (`sort`) return accurate page metadata and ordering.
- An elapsed open offer is treated as expired when retrieved or validated.

**Accept & Concurrency**
- Another user can accept an open, unexpired offer (`200`).
- Owner cannot accept their own offer (`403` / `409`).
- Matched, cancelled, and expired offers cannot be accepted (`409`).
- **Concurrent Acceptance Integration Test:** Using an `ExecutorService` and `CountDownLatch` against real PostgreSQL, fire two simultaneous accept requests for the same offer and verify exactly **one** succeeds (`200 OK`) and **one** fails (`409 Conflict`).

**Cancel**
- Owner can cancel an open, unexpired offer (`200`).
- Non-owner cancellation returns `403`.
- Matched, cancelled, and expired offers cannot be cancelled (`409`).

**Observability**
- `GET /actuator/health` returns `200 OK` with status `UP`.

## 10. Run locally

### Prerequisites
- Java 21
- Maven
- Docker Desktop with Docker Compose (required to run the stack and Testcontainers integration tests)
- PostgreSQL (only if running the app outside Docker)

### Start the full application and database

```bash
docker compose up --build
```

Flyway migrations will automatically initialize the PostgreSQL database schema on startup.

### Run all unit and integration tests

```bash
mvn test
```

*(Note: Ensure Docker is running before executing `mvn test` so Testcontainers can spin up an isolated PostgreSQL container for integration and concurrency tests).*

### Run the application directly

Start PostgreSQL separately, then:

```bash
mvn spring-boot:run
```

### API documentation & Health Check

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **Actuator Health:** `http://localhost:8080/actuator/health`