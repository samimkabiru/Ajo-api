ALTER TABLE cycles
    ADD COLUMN vacated_by_exit_id UUID REFERENCES exit_requests(id);

CREATE INDEX idx_cycles_vacated_by_exit ON cycles (vacated_by_exit_id);

CREATE TABLE shortfall_settlements (
    id                     UUID          PRIMARY KEY,
    claim_id               UUID          NOT NULL REFERENCES shortfall_claims(id),
    funded_by_cycle_id     UUID          NOT NULL REFERENCES cycles(id),
    amount_kobo            BIGINT        NOT NULL,
    ledger_transaction_id  UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT shortfall_settlement_amount_positive CHECK (amount_kobo > 0)
);

-- A claim may be paid in instalments from several vacant cycles: no unique on claim_id.
CREATE INDEX idx_shortfall_settlements_claim ON shortfall_settlements (claim_id);
CREATE INDEX idx_shortfall_settlements_cycle ON shortfall_settlements (funded_by_cycle_id);
