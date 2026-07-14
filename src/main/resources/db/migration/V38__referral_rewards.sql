-- Referral rewards: a two-sided programme.
--   * the referred user gets an immediate discount on their booking, and
--   * the referrer is rewarded (a personal promo voucher) once that booking COMPLETES.
-- Both sides use the same configurable amount.

-- ---------------------------------------------------------------------------
-- Single admin-managed reward configuration. At most one row exists (enforced
-- by the constant-expression unique index below). While it is active and the
-- current time is inside [starts_at, ends_at] its reward_amount applies;
-- otherwise the app falls back to the built-in default (app.referral.*).
-- ---------------------------------------------------------------------------
CREATE TABLE referral_reward_config (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reward_amount            NUMERIC(10, 2) NOT NULL,
    reward_currency          VARCHAR(10)    NOT NULL DEFAULT 'EUR',
    -- Optional validity window. NULL start = effective immediately; NULL end = no expiry.
    starts_at                TIMESTAMPTZ,
    ends_at                  TIMESTAMPTZ,
    -- Optional override of the per-referrer monthly redemption cap (NULL = use default).
    max_monthly_redemptions  INTEGER,
    active                   BOOLEAN        NOT NULL DEFAULT TRUE,

    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT referral_reward_config_amount_nonneg CHECK (reward_amount >= 0),
    CONSTRAINT referral_reward_config_monthly_cap_positive
        CHECK (max_monthly_redemptions IS NULL OR max_monthly_redemptions > 0),
    CONSTRAINT referral_reward_config_window
        CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at >= starts_at)
);

-- Enforce "only one referral reward amount at a time": a unique index on a
-- constant expression permits at most one row in the whole table.
CREATE UNIQUE INDEX referral_reward_config_singleton ON referral_reward_config ((TRUE));

-- ---------------------------------------------------------------------------
-- The discount the referred user received on this booking. Platform-borne
-- (a marketing cost) — the host still earns on the pre-discount price.
-- ---------------------------------------------------------------------------
ALTER TABLE bookings
    ADD COLUMN referral_discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;
