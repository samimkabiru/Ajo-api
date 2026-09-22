DROP INDEX uq_cycles_round_beneficiary;
ALTER TABLE cycles ADD CONSTRAINT uq_cycles_round_beneficiary
    UNIQUE (round_id, beneficiary_id) DEFERRABLE INITIALLY IMMEDIATE;

DROP INDEX uq_round_participants_position;
ALTER TABLE round_participants ADD CONSTRAINT uq_round_participants_position
    UNIQUE (round_id, payout_position) DEFERRABLE INITIALLY IMMEDIATE;

CREATE TABLE position_swap_requests (
    id                         UUID          PRIMARY KEY,
    round_id                   UUID          NOT NULL REFERENCES rounds(id),
    requester_participant_id   UUID          NOT NULL REFERENCES round_participants(id),
    target_participant_id      UUID          NOT NULL REFERENCES round_participants(id),
    requester_position         INTEGER       NOT NULL,
    target_position            INTEGER       NOT NULL,
    status                     VARCHAR(16)   NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    responded_at               TIMESTAMPTZ,
    CONSTRAINT swap_distinct_participants CHECK (requester_participant_id <> target_participant_id),
    CONSTRAINT swap_distinct_positions CHECK (requester_position <> target_position)
);

CREATE UNIQUE INDEX uq_swap_requests_one_outgoing_pending
    ON position_swap_requests (requester_participant_id) WHERE status = 'PENDING';
CREATE INDEX idx_swap_requests_round ON position_swap_requests (round_id);
CREATE INDEX idx_swap_requests_target ON position_swap_requests (target_participant_id);
