CREATE TABLE groups (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(500),
    created_by  UUID          NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    id         UUID         PRIMARY KEY,
    group_id   UUID         NOT NULL REFERENCES groups(id),
    user_id    UUID         NOT NULL REFERENCES users(id),
    role       VARCHAR(16)  NOT NULL,
    joined_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_group_members_group_user ON group_members (group_id, user_id);
CREATE INDEX idx_group_members_user ON group_members (user_id);

CREATE TABLE group_invites (
    id            UUID         PRIMARY KEY,
    group_id      UUID         NOT NULL REFERENCES groups(id),
    phone         VARCHAR(20)  NOT NULL,
    invited_by    UUID         NOT NULL REFERENCES users(id),
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    responded_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_group_invites_pending ON group_invites (group_id, phone) WHERE status = 'PENDING';
CREATE INDEX idx_group_invites_phone ON group_invites (phone);
