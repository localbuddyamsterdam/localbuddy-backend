-- V10: Host earnings ledger — the source of truth for what a host has earned,
-- what is on hold, what is payable, what has been paid out, and clawbacks.
--
-- An EARNING is created when a booking is paid; it stays PENDING until
-- available_at (experience end + hold window, default 72h), then becomes
-- AVAILABLE, then PAID once batched into a payout. Refunds after the host was
-- paid create a negative REVERSAL (clawback) netted against future earnings.

CREATE TABLE host_ledger_entries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    local_profile_id UUID         NOT NULL,
    payment_id       UUID,
    booking_id       UUID,
    payout_id        UUID,

    entry_type       VARCHAR(30)  NOT NULL,            -- EARNING | REVERSAL | ADJUSTMENT
    amount           NUMERIC(12, 2) NOT NULL,          -- negative for reversals/clawbacks
    currency         VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING | AVAILABLE | PAID | REVERSED

    available_at     TIMESTAMPTZ,
    description      TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ledger_local_profile FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id),
    CONSTRAINT fk_ledger_payment       FOREIGN KEY (payment_id)       REFERENCES payments (id),
    CONSTRAINT fk_ledger_booking       FOREIGN KEY (booking_id)       REFERENCES bookings (id),
    CONSTRAINT fk_ledger_payout        FOREIGN KEY (payout_id)        REFERENCES payouts (id)
);

CREATE INDEX idx_ledger_host_status ON host_ledger_entries (local_profile_id, status);
CREATE INDEX idx_ledger_release     ON host_ledger_entries (status, available_at);
CREATE INDEX idx_ledger_payout      ON host_ledger_entries (payout_id);

-- A payment can produce at most one EARNING entry.
CREATE UNIQUE INDEX ux_ledger_earning_per_payment
    ON host_ledger_entries (payment_id)
    WHERE entry_type = 'EARNING' AND payment_id IS NOT NULL;
