CREATE TABLE ledger_accounts (
    id           UUID PRIMARY KEY,
    account_type VARCHAR(32)  NOT NULL,
    owner_id     UUID,
    currency     CHAR(3)      NOT NULL DEFAULT 'NGN',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_ledger_account_owner
    ON ledger_accounts (account_type, owner_id)
    WHERE owner_id IS NOT NULL;

CREATE UNIQUE INDEX uq_ledger_account_singleton
    ON ledger_accounts (account_type)
    WHERE owner_id IS NULL;



CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID         NOT NULL,
    account_id     UUID         NOT NULL REFERENCES ledger_accounts(id),
    amount_kobo    BIGINT       NOT NULL,
    entry_type     VARCHAR(32)  NOT NULL,
    reference_id   UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT amount_not_zero CHECK (amount_kobo <> 0)
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
