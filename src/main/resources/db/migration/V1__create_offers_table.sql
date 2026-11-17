CREATE TABLE offers (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL,
    source_currency VARCHAR(3)      NOT NULL,
    target_currency VARCHAR(3)      NOT NULL,
    amount          NUMERIC(19, 4)  NOT NULL,
    exchange_rate   NUMERIC(19, 8)  NOT NULL,
    target_amount   NUMERIC(19, 4)  NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    version         BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    expires_at      TIMESTAMPTZ     NOT NULL,
    accepted_by     UUID,
    accepted_at     TIMESTAMPTZ,

    CONSTRAINT chk_offers_source_currency CHECK (source_currency IN ('CAD', 'USD', 'GBP', 'NGN')),
    CONSTRAINT chk_offers_target_currency CHECK (target_currency IN ('CAD', 'USD', 'GBP', 'NGN')),
    CONSTRAINT chk_offers_different_currencies CHECK (source_currency <> target_currency),
    CONSTRAINT chk_offers_status CHECK (status IN ('OPEN', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_offers_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_offers_rate_positive CHECK (exchange_rate > 0)
);

CREATE INDEX idx_offers_status_expires_at ON offers (status, expires_at);
CREATE INDEX idx_offers_currency_pair ON offers (source_currency, target_currency);
CREATE INDEX idx_offers_created_at ON offers (created_at DESC);
