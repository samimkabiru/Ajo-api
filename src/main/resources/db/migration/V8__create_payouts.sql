CREATE TABLE payouts (
    id                     UUID          PRIMARY KEY,
    cycle_id               UUID          NOT NULL REFERENCES cycles(id),
    participant_id         UUID          NOT NULL REFERENCES round_participants(id),
    expected_amount_kobo   BIGINT        NOT NULL,
    actual_amount_kobo     BIGINT        NOT NULL,
    method                 VARCHAR(16)   NOT NULL,
    recorded_by            UUID          NOT NULL REFERENCES users(id),
    idempotency_key        VARCHAR(64)   NOT NULL,
    ledger_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT payout_actual_amount_positive CHECK (actual_amount_kobo > 0),
    CONSTRAINT payout_actual_not_above_expected CHECK (actual_amount_kobo <= expected_amount_kobo)
);

CREATE UNIQUE INDEX uq_payouts_cycle ON payouts (cycle_id);
CREATE UNIQUE INDEX uq_payouts_idempotency_key ON payouts (idempotency_key);
CREATE INDEX idx_payouts_participant ON payouts (participant_id);

CREATE TABLE shortfall_claims (
    id             UUID          PRIMARY KEY,
    cycle_id       UUID          NOT NULL REFERENCES cycles(id),
    participant_id UUID          NOT NULL REFERENCES round_participants(id),
    amount_kobo    BIGINT        NOT NULL,
    settled_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT shortfall_amount_positive CHECK (amount_kobo > 0)
);

CREATE UNIQUE INDEX uq_shortfall_claims_cycle ON shortfall_claims (cycle_id);
CREATE INDEX idx_shortfall_claims_participant ON shortfall_claims (participant_id);
