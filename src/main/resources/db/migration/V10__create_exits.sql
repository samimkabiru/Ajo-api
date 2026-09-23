CREATE TABLE exit_requests (
    id                   UUID          PRIMARY KEY,
    round_id             UUID          NOT NULL REFERENCES rounds(id),
    participant_id       UUID          NOT NULL REFERENCES round_participants(id),
    exposure_at_request  BIGINT        NOT NULL,
    status               VARCHAR(24)   NOT NULL,
    requested_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_exit_requests_open
    ON exit_requests (participant_id) WHERE status = 'PENDING_SETTLEMENT';
CREATE INDEX idx_exit_requests_round ON exit_requests (round_id);

CREATE TABLE repayments (
    id                     UUID          PRIMARY KEY,
    participant_id         UUID          NOT NULL REFERENCES round_participants(id),
    amount_kobo            BIGINT        NOT NULL,
    method                 VARCHAR(16)   NOT NULL,
    recorded_by            UUID          NOT NULL REFERENCES users(id),
    idempotency_key        VARCHAR(64)   NOT NULL,
    ledger_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT repayment_amount_positive CHECK (amount_kobo > 0)
);

CREATE UNIQUE INDEX uq_repayments_idempotency_key ON repayments (idempotency_key);
CREATE INDEX idx_repayments_participant ON repayments (participant_id);
