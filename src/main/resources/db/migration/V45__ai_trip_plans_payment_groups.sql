-- AI trip planner (saved shareable itineraries), bundle checkout (one Stripe payment
-- covering several bookings via a payment group), and cached AI review summaries.
--
-- Design notes:
--  * trip_plans stores the ENRICHED plan JSON exactly as served (links, prices, slot ids),
--    addressed publicly by an unguessable token; the row is content, not money.
--  * payment_groups is the money parent: each member booking keeps its OWN payments row
--    (full financial snapshot untouched), children reference the group via
--    payments.payment_group_id, and the Stripe checkout session / payment intent live on
--    the GROUP (children keep their provider ids NULL so the existing partial-unique
--    indexes on payments(provider, provider_checkout_session_id / provider_payment_intent_id)
--    are never violated).
--  * experience_review_summaries caches one AI-generated summary per experience.

CREATE TABLE trip_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(64) NOT NULL,
    city_id UUID NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    party_size INTEGER NOT NULL DEFAULT 2,
    interests VARCHAR(500),
    notes VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    plan JSONB NOT NULL,
    model VARCHAR(60),
    input_tokens INTEGER,
    output_tokens INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_trip_plans_token UNIQUE (token),
    CONSTRAINT chk_trip_plans_date_range CHECK (end_date >= start_date),
    CONSTRAINT chk_trip_plans_party_size CHECK (party_size > 0),
    CONSTRAINT fk_trip_plans_city FOREIGN KEY (city_id) REFERENCES cities (id) ON DELETE CASCADE
);

CREATE INDEX idx_trip_plans_city ON trip_plans (city_id);
CREATE INDEX idx_trip_plans_created_at ON trip_plans (created_at);

CREATE TABLE payment_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_token VARCHAR(64) NOT NULL,
    trip_plan_id UUID,
    logged_in_user_id UUID,
    guest_email VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(40) NOT NULL DEFAULT 'STRIPE',
    total_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    gift_card_id UUID,
    -- Original amount drawn from the gift card for the whole group (immutable once set;
    -- the return-on-failure is idempotency-guarded by gift_card_returned_at instead of zeroing).
    gift_card_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    gift_card_returned_at TIMESTAMPTZ,
    provider_checkout_session_id VARCHAR(255),
    provider_payment_intent_id VARCHAR(255),
    checkout_url TEXT,
    failure_reason VARCHAR(500),
    paid_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_groups_token UNIQUE (group_token),
    CONSTRAINT chk_payment_groups_gift_card CHECK (gift_card_amount >= 0 AND gift_card_amount <= total_amount),
    CONSTRAINT fk_payment_groups_trip_plan FOREIGN KEY (trip_plan_id) REFERENCES trip_plans (id) ON DELETE SET NULL,
    CONSTRAINT fk_payment_groups_user FOREIGN KEY (logged_in_user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX ux_payment_groups_session
    ON payment_groups (provider, provider_checkout_session_id)
    WHERE provider_checkout_session_id IS NOT NULL;
CREATE INDEX idx_payment_groups_status ON payment_groups (status);

ALTER TABLE payments ADD COLUMN payment_group_id UUID;
ALTER TABLE payments
    ADD CONSTRAINT fk_payments_payment_group
    FOREIGN KEY (payment_group_id) REFERENCES payment_groups (id) ON DELETE SET NULL;
CREATE INDEX idx_payments_payment_group ON payments (payment_group_id);

CREATE TABLE experience_review_summaries (
    experience_id UUID PRIMARY KEY,
    summary TEXT NOT NULL,
    highlights JSONB,
    concerns JSONB,
    review_count INTEGER NOT NULL DEFAULT 0,
    average_rating NUMERIC(3, 2),
    model VARCHAR(60),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_review_summaries_experience FOREIGN KEY (experience_id) REFERENCES experiences (id) ON DELETE CASCADE
);
