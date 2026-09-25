ALTER TABLE payouts
    ADD COLUMN arrears_withheld_kobo BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payouts
    ADD CONSTRAINT payout_arrears_not_negative CHECK (arrears_withheld_kobo >= 0);
