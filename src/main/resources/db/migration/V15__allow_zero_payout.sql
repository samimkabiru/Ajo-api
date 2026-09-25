-- A beneficiary whose arrears consume the whole pot collects nothing: a legitimate payout of zero.
ALTER TABLE payouts DROP CONSTRAINT payout_actual_amount_positive;
ALTER TABLE payouts ADD CONSTRAINT payout_actual_amount_not_negative CHECK (actual_amount_kobo >= 0);

-- A zero payout posts nothing, so it has no ledger transaction of its own.
ALTER TABLE payouts ALTER COLUMN ledger_transaction_id DROP NOT NULL;
