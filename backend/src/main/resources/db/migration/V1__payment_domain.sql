CREATE TABLE orders (
    id                  VARCHAR(128) PRIMARY KEY,
    owner_id            VARCHAR(255) NOT NULL,
    amount_minor        BIGINT NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status              VARCHAR(24) NOT NULL CHECK (status IN ('PAYABLE', 'PAID', 'CANCELLED')),
    created_at_epoch_ms BIGINT NOT NULL
);

CREATE TABLE payment_attempts (
    id                  UUID PRIMARY KEY,
    owner_id            VARCHAR(255) NOT NULL,
    order_id            VARCHAR(128) NOT NULL REFERENCES orders(id),
    idempotency_key     VARCHAR(255) NOT NULL,
    stripe_intent_id    VARCHAR(255) UNIQUE,
    amount_minor        BIGINT NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    provider            VARCHAR(32) NOT NULL,
    status              VARCHAR(40) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    last_stripe_event_created_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_owner_idempotency UNIQUE(owner_id, idempotency_key)
);

CREATE INDEX ix_payment_attempt_order_owner ON payment_attempts(order_id, owner_id);
CREATE UNIQUE INDEX uq_one_active_payment_per_order
    ON payment_attempts(order_id, owner_id)
    WHERE status IN ('INITIATING', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING', 'TIMED_OUT', 'UNKNOWN');

CREATE TABLE processed_stripe_events (
    event_id            VARCHAR(255) PRIMARY KEY,
    processed_at_epoch_ms BIGINT NOT NULL
);
