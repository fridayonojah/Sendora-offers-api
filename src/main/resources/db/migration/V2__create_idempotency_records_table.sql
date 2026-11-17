CREATE TABLE idempotency_records (
    id              UUID            PRIMARY KEY,
    idempotency_key VARCHAR(255)    NOT NULL,
    request_hash    VARCHAR(64)     NOT NULL,
    offer_id        UUID            NOT NULL REFERENCES offers (id),
    created_at      TIMESTAMPTZ     NOT NULL,

    CONSTRAINT uk_idempotency_records_key UNIQUE (idempotency_key)
);
