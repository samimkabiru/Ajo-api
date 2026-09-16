CREATE TABLE users (
    id              UUID          PRIMARY KEY,
    phone           VARCHAR(20)   NOT NULL,
    phone_verified  BOOLEAN       NOT NULL DEFAULT false,
    email           VARCHAR(255),
    email_verified  BOOLEAN       NOT NULL DEFAULT false,
    password_hash   VARCHAR(255)  NOT NULL,
    full_name       VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_users_phone ON users (phone);
CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE email IS NOT NULL;


CREATE TABLE refresh_tokens (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id),
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
