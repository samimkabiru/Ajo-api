CREATE TABLE buy_ins (
    id                     UUID          PRIMARY KEY,
    round_id               UUID          NOT NULL REFERENCES rounds(id),
    exit_request_id        UUID          NOT NULL REFERENCES exit_requests(id),
    participant_id         UUID          NOT NULL REFERENCES round_participants(id),
    leaver_user_id         UUID          NOT NULL REFERENCES users(id),
    replacement_user_id    UUID          NOT NULL REFERENCES users(id),
    amount_kobo            BIGINT        NOT NULL,
    method                 VARCHAR(16)   NOT NULL,
    recorded_by            UUID          NOT NULL REFERENCES users(id),
    idempotency_key        VARCHAR(64)   NOT NULL,
    buy_in_transaction_id  UUID          NOT NULL,
    refund_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT buy_in_amount_positive CHECK (amount_kobo > 0),
    CONSTRAINT buy_in_distinct_parties CHECK (leaver_user_id <> replacement_user_id)
);

CREATE UNIQUE INDEX uq_buy_ins_exit_request ON buy_ins (exit_request_id);
CREATE UNIQUE INDEX uq_buy_ins_idempotency_key ON buy_ins (idempotency_key);
CREATE INDEX idx_buy_ins_round ON buy_ins (round_id);
