-- Failed-login counters, one row per normalised phone number. Keyed on the submitted phone,
-- not on a user: an unregistered number is counted exactly like a registered one, so a 429
-- never reveals which numbers have accounts. Hence no foreign key to users.
--
-- `blocked_until` set and in the future means login is refused for that number. When a block
-- is set, `failed_count` returns to 0 and `window_started_at` moves to `blocked_until`, so the
-- number gets a full set of attempts once the block lifts.
--
-- Growth: a row is deleted only by a successful login or password reset. Legitimate use stays
-- small, but a number that only ever fails — e.g. one of a million sprayed by an attacker —
-- leaves its row behind for good. So the table grows with every distinct number that has
-- ever failed a login. See DESIGN.md, "Known limits".
CREATE TABLE login_attempt_counters (
    phone              VARCHAR(20)  PRIMARY KEY,
    failed_count       INTEGER      NOT NULL DEFAULT 0,
    window_started_at  TIMESTAMPTZ  NOT NULL,
    blocked_until      TIMESTAMPTZ,
    CONSTRAINT login_attempt_failed_count_not_negative CHECK (failed_count >= 0)
);
