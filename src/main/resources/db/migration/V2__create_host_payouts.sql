-- V2: Host payouts — track money owed to and disbursed to hosts.
--
-- Forward migration on top of the consolidated baseline (V1). Earnings are derived
-- from existing payments (local_payout_amount); payouts + payout_items record what
-- has been disbursed, so a host portion is never paid twice.

ALTER TABLE local_profiles
    ADD COLUMN stripe_connect_account_id VARCHAR(255),
    ADD COLUMN payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE payouts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    local_profile_id     UUID NOT NULL,
    amount               NUMERIC(12, 2) NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'EUR',
    status               VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    provider             VARCHAR(40) NOT NULL DEFAULT 'STRIPE',
    provider_transfer_id VARCHAR(255),
    notes                TEXT,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at              TIMESTAMPTZ,

    CONSTRAINT fk_payouts_local_profile FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id),
    CONSTRAINT chk_payouts_amount_non_negative CHECK (amount >= 0)
);

CREATE INDEX idx_payouts_local_profile_id ON payouts (local_profile_id);
CREATE INDEX idx_payouts_status           ON payouts (status);

CREATE TABLE payout_items (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id  UUID NOT NULL,
    payment_id UUID NOT NULL,
    amount     NUMERIC(12, 2) NOT NULL,

    CONSTRAINT fk_payout_items_payout  FOREIGN KEY (payout_id)  REFERENCES payouts (id) ON DELETE CASCADE,
    CONSTRAINT fk_payout_items_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    -- A given payment's host portion can only be disbursed once.
    CONSTRAINT ux_payout_items_payment UNIQUE (payment_id)
);

CREATE INDEX idx_payout_items_payout_id ON payout_items (payout_id);
