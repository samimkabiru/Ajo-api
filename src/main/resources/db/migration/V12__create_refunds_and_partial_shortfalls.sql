ALTER TABLE shortfall_claims
    ADD COLUMN settled_amount_kobo BIGINT NOT NULL DEFAULT 0;

ALTER TABLE shortfall_claims
    ADD CONSTRAINT shortfall_settled_within_amount
    CHECK (settled_amount_kobo >= 0 AND settled_amount_kobo <= amount_kobo);

CREATE TABLE refunds (
    id                     UUID          PRIMARY KEY,
    cycle_id               UUID          NOT NULL REFERENCES cycles(id),
    exit_request_id        UUID          NOT NULL REFERENCES exit_requests(id),
    participant_id         UUID          NOT NULL REFERENCES round_participants(id),
    user_id                UUID          NOT NULL REFERENCES users(id),
    expected_amount_kobo   BIGINT        NOT NULL,
    actual_amount_kobo     BIGINT        NOT NULL,
    ledger_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT refund_actual_amount_positive CHECK (actual_amount_kobo > 0),
    CONSTRAINT refund_actual_not_above_expected CHECK (actual_amount_kobo <= expected_amount_kobo)
);

CREATE UNIQUE INDEX uq_refunds_exit_request ON refunds (exit_request_id);
CREATE INDEX idx_refunds_cycle ON refunds (cycle_id);
