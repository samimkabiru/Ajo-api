-- One-time codes sent by SMS. `purpose` lets password reset (slice 10) reuse this table.
-- The code itself is never stored: only its BCrypt hash.
-- `consumed_at` means "no longer usable" — set on success, when superseded by a new request,
-- on expiry, and when attempts run out — which is what makes the partial unique index work.
CREATE TABLE verification_codes (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users(id),
    purpose      VARCHAR(32)   NOT NULL,
    code_hash    VARCHAR(255)  NOT NULL,
    attempts     INTEGER       NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ   NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT verification_attempts_not_negative CHECK (attempts >= 0)
);

-- At most one live code per user per purpose, enforced by the database.
CREATE UNIQUE INDEX uq_verification_codes_active
    ON verification_codes (user_id, purpose) WHERE consumed_at IS NULL;

-- The rate-limit query: codes for a user created within a window.
CREATE INDEX idx_verification_codes_user_created ON verification_codes (user_id, created_at);
