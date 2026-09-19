CREATE TABLE rounds (
    id                        UUID          PRIMARY KEY,
    group_id                  UUID          NOT NULL REFERENCES groups(id),
    contribution_amount_kobo  BIGINT        NOT NULL,
    status                    VARCHAR(16)   NOT NULL,
    created_by                UUID          NOT NULL REFERENCES users(id),
    activated_at              TIMESTAMPTZ,
    first_payout_date         DATE,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT contribution_amount_positive CHECK (contribution_amount_kobo > 0)
);

CREATE INDEX idx_rounds_group ON rounds (group_id);

CREATE UNIQUE INDEX uq_rounds_active_per_group
    ON rounds (group_id)
    WHERE status IN ('FORMING', 'ACTIVE');


CREATE TABLE round_participants (
    id                UUID         PRIMARY KEY,
    round_id          UUID         NOT NULL REFERENCES rounds(id),
    user_id           UUID         NOT NULL REFERENCES users(id),
    payout_position   INTEGER,
    status            VARCHAR(16)  NOT NULL,
    joined_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_round_participants_round_user ON round_participants (round_id, user_id);
CREATE UNIQUE INDEX uq_round_participants_position ON round_participants (round_id, payout_position);
CREATE INDEX idx_round_participants_user ON round_participants (user_id);


CREATE TABLE cycles (
    id              UUID         PRIMARY KEY,
    round_id        UUID         NOT NULL REFERENCES rounds(id),
    cycle_number    INTEGER      NOT NULL,
    beneficiary_id  UUID         REFERENCES round_participants(id),
    opens_on        DATE         NOT NULL,
    due_on          DATE         NOT NULL,
    payout_on       DATE         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cycles_round_number ON cycles (round_id, cycle_number);
CREATE UNIQUE INDEX uq_cycles_round_beneficiary ON cycles (round_id, beneficiary_id);
CREATE INDEX idx_cycles_round ON cycles (round_id);
