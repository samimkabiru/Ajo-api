CREATE TABLE contributions (
    id                     UUID          PRIMARY KEY,
    cycle_id               UUID          NOT NULL REFERENCES cycles(id),
    participant_id         UUID          NOT NULL REFERENCES round_participants(id),
    amount_kobo            BIGINT        NOT NULL,
    method                 VARCHAR(16)   NOT NULL,
    recorded_by            UUID          NOT NULL REFERENCES users(id),
    idempotency_key        VARCHAR(64)   NOT NULL,
    ledger_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT contribution_amount_positive CHECK (amount_kobo > 0)
);

CREATE UNIQUE INDEX uq_contributions_cycle_participant ON contributions (cycle_id, participant_id);
CREATE UNIQUE INDEX uq_contributions_idempotency_key ON contributions (idempotency_key);
CREATE INDEX idx_contributions_cycle ON contributions (cycle_id);
CREATE INDEX idx_contributions_participant ON contributions (participant_id);
