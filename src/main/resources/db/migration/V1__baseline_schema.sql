-- =====================================================================
-- LocalBuddy — CONSOLIDATED FLYWAY BASELINE (fresh-database deployment)
-- =====================================================================
-- Single baseline that reproduces the FINAL merged schema for the
-- localbuddy-backend app (Spring Boot 4 / PostgreSQL / Hibernate).
--
-- Source of truth: the JPA @Entity classes under com.localbuddy.**.
-- Reference:        the historical Flyway migrations V1..V32 (for the
--                   non-entity audit_logs table, CHECK constraints, FK
--                   on-delete behavior, indexes, partial-unique indexes,
--                   and seed/reference data in their FINAL state).
--
-- ACCEPTANCE: `spring.jpa.hibernate.ddl-auto: validate` must pass against
-- this schema. Where an entity and the old migrations disagreed, the
-- ENTITY wins (Hibernate validates against the entity). Such cases are
-- annotated inline with "ENTITY-WINS:".
--
-- This file lives under src/main/resources/db/baseline (a STAGING dir
-- that Flyway does NOT scan). It is not applied automatically.
--
-- Statement order:
--   1. extension
--   2. lookup / independent tables
--   3. core domain tables (dependency order)
--   4. feature tables
--   5. join tables
--   6. seed / reference data (idempotent)
-- All enums are stored as VARCHAR. UUID PKs default to gen_random_uuid()
-- (pgcrypto), except where the entity assigns the id itself.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. EXTENSIONS
-- ---------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- =====================================================================
-- 1. IDENTITY & ACCESS
-- =====================================================================

-- users (entity: com.localbuddy.user.User)
-- ENTITY-WINS: rating_avg + total_reviews exist on the entity but were
-- never added by a migration for the users table; included here.
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name       VARCHAR(150)  NOT NULL,
    email           VARCHAR(255)  NOT NULL UNIQUE,
    phone           VARCHAR(30),
    password_hash   VARCHAR(255),
    role            VARCHAR(30)   NOT NULL,
    status          VARCHAR(30)   NOT NULL,
    email_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    rating_avg      NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    total_reviews   INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email  ON users (email);
CREATE INDEX idx_users_role   ON users (role);
CREATE INDEX idx_users_status ON users (status);


-- audit_logs (NO entity — preserved from migration V1)
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       UUID,
    old_value_json  JSONB,
    new_value_json  JSONB,
    ip_address      VARCHAR(100),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_actor_user_id   ON audit_logs (actor_user_id);
CREATE INDEX idx_audit_logs_entity_type_id  ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at       ON audit_logs (created_at);


-- =====================================================================
-- 2. REFERENCE / LOOKUP POOLS
-- =====================================================================

-- cities (entity: com.localbuddy.experience.City)
CREATE TABLE cities (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(100) NOT NULL,
    slug           VARCHAR(120) NOT NULL UNIQUE,
    country        VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order  INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_cities_name        ON cities (LOWER(name));
CREATE INDEX        idx_cities_active        ON cities (active);
CREATE INDEX        idx_cities_display_order ON cities (display_order);


-- experience_categories (entity: com.localbuddy.experience.ExperienceCategory)
CREATE TABLE experience_categories (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(100) NOT NULL,
    slug           VARCHAR(120) NOT NULL UNIQUE,
    description    TEXT,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order  INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_experience_categories_name      ON experience_categories (LOWER(name));
CREATE INDEX        idx_experience_categories_active        ON experience_categories (active);
CREATE INDEX        idx_experience_categories_display_order ON experience_categories (display_order);


-- =====================================================================
-- 3. HOST PROFILES
-- =====================================================================

-- local_profiles (entity: com.localbuddy.localprofile.LocalProfile)
-- Final shape after the V28 restructure (renames, new mandatory fields,
-- optional banking fields, dropped occupation/buddy_city/current_city/interests).
-- ENTITY-WINS: bio & profile_photo_url are NOT NULL on the entity (V2 created
-- them nullable; V28 enforced NOT NULL). experience_languages is jsonb.
CREATE TABLE local_profiles (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id                     UUID          NOT NULL UNIQUE,
    display_name                VARCHAR(150)  NOT NULL,
    bio                         TEXT          NOT NULL,
    phone_number                VARCHAR(40)   NOT NULL,
    host_city                   VARCHAR(100)  NOT NULL,
    zip_code                    VARCHAR(20)   NOT NULL,
    country                     VARCHAR(100)  NOT NULL,

    experience_languages        JSONB,

    motivation                  TEXT          NOT NULL,
    experience_info             TEXT          NOT NULL,
    profile_photo_url           TEXT          NOT NULL,

    verification_status         VARCHAR(40)   NOT NULL DEFAULT 'NOT_STARTED',
    approval_status             VARCHAR(40)   NOT NULL DEFAULT 'DRAFT',

    admin_review_note           TEXT,
    rejection_reason            TEXT,
    changes_requested_reason    TEXT,
    reviewed_at                 TIMESTAMPTZ,
    submitted_at                TIMESTAMPTZ,
    resubmitted_at              TIMESTAMPTZ,

    legal_first_name            VARCHAR(120)  NOT NULL,
    legal_last_name             VARCHAR(120)  NOT NULL,
    preferred_name              VARCHAR(120)  NOT NULL,
    current_address             TEXT          NOT NULL,

    account_number              VARCHAR(64),
    account_name                VARCHAR(150),
    swift_code                  VARCHAR(32),

    verification_provider       VARCHAR(80),
    verification_reference_id   VARCHAR(255),
    verification_started_at     TIMESTAMPTZ,
    verification_completed_at   TIMESTAMPTZ,
    verification_failure_reason TEXT,

    rating_avg                  NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    total_reviews               INTEGER       NOT NULL DEFAULT 0,

    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_local_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_local_profiles_host_city           ON local_profiles (host_city);
CREATE INDEX idx_local_profiles_country             ON local_profiles (country);
CREATE INDEX idx_local_profiles_verification_status ON local_profiles (verification_status);
CREATE INDEX idx_local_profiles_approval_status     ON local_profiles (approval_status);
CREATE INDEX idx_local_profiles_rating_avg          ON local_profiles (rating_avg);
CREATE INDEX idx_local_profiles_submitted_at        ON local_profiles (submitted_at);
CREATE INDEX idx_local_profiles_reviewed_at         ON local_profiles (reviewed_at);


-- =====================================================================
-- 4. EXPERIENCES
-- =====================================================================

-- experiences (entity: com.localbuddy.experience.Experience)
-- Final shape: old free-text city/country/category columns dropped; FK
-- to cities + experience_categories. category_id is OPTIONAL (V21).
-- ENTITY-WINS: new columns short_description, end_location, transport_mode,
-- inclusions, exclusions, reasons_to_book, minimum_age, booking_mode,
-- private_price were never added by a migration; included here.
CREATE TABLE experiences (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    local_profile_id  UUID          NOT NULL,
    category_id       UUID,
    city_id           UUID          NOT NULL,

    title             VARCHAR(150)  NOT NULL,
    slug              VARCHAR(180)  NOT NULL UNIQUE,
    description       TEXT          NOT NULL,
    short_description VARCHAR(300),

    meeting_area      VARCHAR(150),
    end_location      VARCHAR(255),
    transport_mode    VARCHAR(40),

    inclusions        TEXT,
    exclusions        TEXT,
    reasons_to_book   TEXT,

    minimum_age       INTEGER       NOT NULL DEFAULT 0,
    duration_minutes  INTEGER       NOT NULL,

    price_amount      NUMERIC(10, 2) NOT NULL,
    booking_mode      VARCHAR(40)   NOT NULL DEFAULT 'SHARED',
    private_price     NUMERIC(10, 2),
    currency          VARCHAR(3)    NOT NULL DEFAULT 'EUR',
    max_guests        INTEGER       NOT NULL,

    safety_notes      TEXT,
    status            VARCHAR(40)   NOT NULL DEFAULT 'DRAFT',

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_experiences_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_experiences_category
        FOREIGN KEY (category_id) REFERENCES experience_categories (id),
    CONSTRAINT fk_experiences_city
        FOREIGN KEY (city_id) REFERENCES cities (id),

    CONSTRAINT chk_experiences_duration_positive   CHECK (duration_minutes > 0),
    CONSTRAINT chk_experiences_price_non_negative   CHECK (price_amount >= 0),
    CONSTRAINT chk_experiences_max_guests_positive  CHECK (max_guests > 0)
);

CREATE INDEX idx_experiences_local_profile_id ON experiences (local_profile_id);
CREATE INDEX idx_experiences_category_id      ON experiences (category_id);
CREATE INDEX idx_experiences_city_id          ON experiences (city_id);
CREATE INDEX idx_experiences_status           ON experiences (status);


-- experience_category_links (entity: Experience.categories @ManyToMany)
-- ENTITY-WINS: this join table has NO migration; created from the entity mapping.
CREATE TABLE experience_category_links (
    experience_id UUID NOT NULL,
    category_id   UUID NOT NULL,
    PRIMARY KEY (experience_id, category_id),
    CONSTRAINT fk_ecl_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id) ON DELETE CASCADE,
    CONSTRAINT fk_ecl_category
        FOREIGN KEY (category_id) REFERENCES experience_categories (id)
);

CREATE INDEX idx_ecl_category_id ON experience_category_links (category_id);


-- =====================================================================
-- 5. AVAILABILITY
-- =====================================================================

-- availability_slots (entity: com.localbuddy.availability.AvailabilitySlot)
CREATE TABLE availability_slots (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    experience_id     UUID        NOT NULL,
    local_profile_id  UUID        NOT NULL,

    start_time        TIMESTAMPTZ NOT NULL,
    end_time          TIMESTAMPTZ NOT NULL,

    capacity          INTEGER     NOT NULL DEFAULT 1,
    booked_count      INTEGER     NOT NULL DEFAULT 0,

    status            VARCHAR(40) NOT NULL DEFAULT 'AVAILABLE',

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_availability_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id) ON DELETE CASCADE,
    CONSTRAINT fk_availability_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id) ON DELETE CASCADE,

    CONSTRAINT chk_availability_time_valid              CHECK (end_time > start_time),
    CONSTRAINT chk_availability_capacity_positive        CHECK (capacity > 0),
    CONSTRAINT chk_availability_booked_count_non_negative CHECK (booked_count >= 0),
    CONSTRAINT chk_availability_booked_count_capacity     CHECK (booked_count <= capacity),
    -- redundant guards added by V25 (kept for parity with the historical schema)
    CONSTRAINT chk_availability_slots_booked_count_non_negative  CHECK (booked_count >= 0),
    CONSTRAINT chk_availability_slots_capacity_positive          CHECK (capacity >= 1),
    CONSTRAINT chk_availability_slots_booked_count_not_over_capacity CHECK (booked_count <= capacity)
);

CREATE INDEX idx_availability_experience_id  ON availability_slots (experience_id);
CREATE INDEX idx_availability_local_profile_id ON availability_slots (local_profile_id);
CREATE INDEX idx_availability_start_time      ON availability_slots (start_time);
CREATE INDEX idx_availability_status          ON availability_slots (status);
CREATE INDEX idx_availability_experience_status_start_time
    ON availability_slots (experience_id, status, start_time);


-- =====================================================================
-- 6. PROMOTIONS & REFERRALS  (defined before bookings: bookings FK them)
-- =====================================================================

-- promo_codes (entity: com.localbuddy.promo.PromoCode)
CREATE TABLE promo_codes (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                     VARCHAR(80)  NOT NULL UNIQUE,
    description              TEXT,

    discount_type            VARCHAR(40)  NOT NULL,
    discount_value           NUMERIC(10, 2) NOT NULL,

    currency                 VARCHAR(10),
    max_discount_amount      NUMERIC(10, 2),
    min_booking_amount       NUMERIC(10, 2),

    max_total_redemptions    INTEGER,
    max_redemptions_per_user INTEGER,
    current_redemptions      INTEGER      NOT NULL DEFAULT 0,

    starts_at                TIMESTAMPTZ,
    expires_at               TIMESTAMPTZ,

    active                   BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_promo_codes_code       ON promo_codes (code);
CREATE INDEX idx_promo_codes_active     ON promo_codes (active);
CREATE INDEX idx_promo_codes_expires_at ON promo_codes (expires_at);


-- referral_codes (entity: com.localbuddy.referral.ReferralCode)
CREATE TABLE referral_codes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id       UUID         NOT NULL,
    code                VARCHAR(80)  NOT NULL UNIQUE,

    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    max_redemptions     INTEGER,
    current_redemptions INTEGER      NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_referral_codes_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users (id)
);

CREATE INDEX idx_referral_codes_owner_user_id ON referral_codes (owner_user_id);
CREATE INDEX idx_referral_codes_code          ON referral_codes (code);
CREATE INDEX idx_referral_codes_active        ON referral_codes (active);


-- =====================================================================
-- 7. BOOKINGS  (depends on users, local_profiles, experiences,
--               availability_slots, promo_codes, referral_codes)
-- =====================================================================

-- bookings (entity: com.localbuddy.booking.Booking)
-- Final shape includes guest support, consent capture, promo/referral
-- linkage, and the private-buyout columns (is_private, seats_blocked,
-- private_discount_amount).
CREATE TABLE bookings (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    booking_reference         VARCHAR(40)  NOT NULL UNIQUE,

    traveler_user_id          UUID,
    guest_name                VARCHAR(150),
    guest_email               VARCHAR(255),
    guest_phone               VARCHAR(40),
    guest_email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    guest_phone_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    booking_source            VARCHAR(40)  NOT NULL DEFAULT 'LOGGED_IN_USER',

    local_profile_id          UUID         NOT NULL,
    experience_id             UUID         NOT NULL,
    availability_slot_id      UUID         NOT NULL,

    guests_count              INTEGER      NOT NULL DEFAULT 1,
    is_private                BOOLEAN      NOT NULL DEFAULT FALSE,
    seats_blocked             INTEGER      NOT NULL,

    status                    VARCHAR(40)  NOT NULL DEFAULT 'REQUESTED',

    price_per_guest           NUMERIC(10, 2) NOT NULL,
    total_amount              NUMERIC(10, 2) NOT NULL,
    currency                  VARCHAR(3)   NOT NULL DEFAULT 'EUR',

    traveler_note             TEXT,
    local_response_note       TEXT,
    cancellation_reason       TEXT,

    guest_terms_accepted      BOOLEAN      NOT NULL DEFAULT FALSE,
    guest_safety_accepted     BOOLEAN      NOT NULL DEFAULT FALSE,
    guest_liability_accepted  BOOLEAN      NOT NULL DEFAULT FALSE,
    guest_consent_version     VARCHAR(80),
    guest_consent_accepted_at TIMESTAMPTZ,
    guest_consent_ip_address  VARCHAR(120),
    guest_consent_user_agent  TEXT,

    requested_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    accepted_at               TIMESTAMPTZ,
    declined_at               TIMESTAMPTZ,
    cancelled_at              TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,

    promo_code_id             UUID,
    referral_code_id          UUID,
    original_amount           NUMERIC(10, 2),
    discount_amount           NUMERIC(10, 2) NOT NULL DEFAULT 0,
    private_discount_amount   NUMERIC(10, 2) NOT NULL DEFAULT 0,
    promo_code_text           VARCHAR(80),
    referral_code_text        VARCHAR(80),

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_bookings_traveler_user
        FOREIGN KEY (traveler_user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id),
    CONSTRAINT fk_bookings_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id),
    CONSTRAINT fk_bookings_availability_slot
        FOREIGN KEY (availability_slot_id) REFERENCES availability_slots (id),
    CONSTRAINT fk_bookings_promo_code
        FOREIGN KEY (promo_code_id) REFERENCES promo_codes (id),
    CONSTRAINT fk_bookings_referral_code
        FOREIGN KEY (referral_code_id) REFERENCES referral_codes (id),

    CONSTRAINT chk_bookings_guests_count_positive       CHECK (guests_count > 0),
    CONSTRAINT chk_bookings_price_per_guest_non_negative CHECK (price_per_guest >= 0),
    CONSTRAINT chk_bookings_total_amount_non_negative    CHECK (total_amount >= 0),
    CONSTRAINT chk_bookings_private_discount_non_negative CHECK (private_discount_amount >= 0),
    CONSTRAINT chk_bookings_seats_blocked_positive       CHECK (seats_blocked >= 1),
    CONSTRAINT chk_bookings_traveler_or_guest
        CHECK (
            traveler_user_id IS NOT NULL
            OR (guest_name IS NOT NULL AND guest_email IS NOT NULL AND guest_phone IS NOT NULL)
        )
);

CREATE INDEX idx_bookings_traveler_user_id     ON bookings (traveler_user_id);
CREATE INDEX idx_bookings_local_profile_id     ON bookings (local_profile_id);
CREATE INDEX idx_bookings_experience_id        ON bookings (experience_id);
CREATE INDEX idx_bookings_availability_slot_id ON bookings (availability_slot_id);
CREATE INDEX idx_bookings_status               ON bookings (status);
CREATE INDEX idx_bookings_requested_at         ON bookings (requested_at);
CREATE INDEX idx_bookings_traveler_status      ON bookings (traveler_user_id, status);
CREATE INDEX idx_bookings_local_status         ON bookings (local_profile_id, status);
CREATE INDEX idx_bookings_guest_email          ON bookings (guest_email);
CREATE INDEX idx_bookings_guest_phone          ON bookings (guest_phone);
CREATE INDEX idx_bookings_booking_source       ON bookings (booking_source);
CREATE INDEX idx_bookings_promo_code_id        ON bookings (promo_code_id);
CREATE INDEX idx_bookings_referral_code_id     ON bookings (referral_code_id);
CREATE INDEX idx_bookings_promo_code_text      ON bookings (promo_code_text);
CREATE INDEX idx_bookings_referral_code_text   ON bookings (referral_code_text);
CREATE INDEX idx_bookings_guest_consent_version     ON bookings (guest_consent_version);
CREATE INDEX idx_bookings_guest_consent_accepted_at ON bookings (guest_consent_accepted_at);
CREATE INDEX idx_bookings_traveler_slot_status
    ON bookings (traveler_user_id, availability_slot_id, status);
CREATE INDEX idx_bookings_local_profile_status_requested
    ON bookings (local_profile_id, status, requested_at DESC);
CREATE INDEX idx_bookings_status_requested
    ON bookings (status, requested_at DESC);

-- Partial-unique guards: one active booking per traveler/guest per slot
-- (widened by V30 to cover REQUESTED/ACCEPTED/PENDING_PAYMENT/CONFIRMED).
CREATE UNIQUE INDEX ux_bookings_active_traveler_slot
    ON bookings (traveler_user_id, availability_slot_id)
    WHERE status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED');

CREATE UNIQUE INDEX ux_bookings_active_guest_slot
    ON bookings (LOWER(guest_email), availability_slot_id)
    WHERE traveler_user_id IS NULL
      AND status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED');


-- booking_reference_sequences (entity: com.localbuddy.booking.BookingReferenceSequence)
-- NOTE: entity uses LocalDate PK + LocalDateTime timestamps (NOT timestamptz).
CREATE TABLE booking_reference_sequences (
    booking_date  DATE PRIMARY KEY,
    last_sequence INTEGER   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- cancellation_refund_policies (entity: com.localbuddy.booking.CancellationRefundPolicy)
-- NOTE: id is application-assigned (no @GeneratedValue) -> no default.
CREATE TABLE cancellation_refund_policies (
    id                     UUID PRIMARY KEY,
    name                   VARCHAR(150) NOT NULL,
    cancelled_by           VARCHAR(40)  NOT NULL,
    min_hours_before_start NUMERIC(8, 2) NOT NULL,
    max_hours_before_start NUMERIC(8, 2),
    refund_percentage      NUMERIC(5, 2) NOT NULL,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cancellation_refund_policies_cancelled_by ON cancellation_refund_policies (cancelled_by);
CREATE INDEX idx_cancellation_refund_policies_active       ON cancellation_refund_policies (active);


-- =====================================================================
-- 8. PROMO / REFERRAL REDEMPTIONS  (depend on bookings)
-- =====================================================================

-- promo_code_redemptions (entity: com.localbuddy.promo.PromoCodeRedemption)
CREATE TABLE promo_code_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promo_code_id   UUID         NOT NULL,
    user_id         UUID,
    booking_id      UUID,
    guest_email     VARCHAR(255),

    discount_amount NUMERIC(10, 2) NOT NULL,
    redeemed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_promo_redemptions_promo_code
        FOREIGN KEY (promo_code_id) REFERENCES promo_codes (id),
    CONSTRAINT fk_promo_redemptions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_promo_redemptions_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
);

CREATE INDEX idx_promo_redemptions_promo_code_id ON promo_code_redemptions (promo_code_id);
CREATE INDEX idx_promo_redemptions_user_id       ON promo_code_redemptions (user_id);
CREATE INDEX idx_promo_redemptions_booking_id    ON promo_code_redemptions (booking_id);
CREATE INDEX idx_promo_redemptions_guest_email   ON promo_code_redemptions (guest_email);
CREATE INDEX idx_promo_redemptions_promo_user    ON promo_code_redemptions (promo_code_id, user_id);
CREATE INDEX idx_promo_redemptions_promo_guest   ON promo_code_redemptions (promo_code_id, LOWER(guest_email));
CREATE INDEX idx_promo_redemptions_booking       ON promo_code_redemptions (booking_id);

CREATE UNIQUE INDEX uk_promo_redemptions_booking_id
    ON promo_code_redemptions (booking_id)
    WHERE booking_id IS NOT NULL;


-- referral_redemptions (entity: com.localbuddy.referral.ReferralRedemption)
CREATE TABLE referral_redemptions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referral_code_id     UUID         NOT NULL,
    referred_user_id     UUID,
    referred_guest_email VARCHAR(255),
    booking_id           UUID,

    reward_status        VARCHAR(40)  NOT NULL DEFAULT 'PENDING',
    reward_amount        NUMERIC(10, 2),
    reward_currency      VARCHAR(10),

    redeemed_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reward_processed_at  TIMESTAMPTZ,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_referral_redemptions_referral_code
        FOREIGN KEY (referral_code_id) REFERENCES referral_codes (id),
    CONSTRAINT fk_referral_redemptions_referred_user
        FOREIGN KEY (referred_user_id) REFERENCES users (id),
    CONSTRAINT fk_referral_redemptions_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
);

CREATE INDEX idx_referral_redemptions_referral_code_id ON referral_redemptions (referral_code_id);
CREATE INDEX idx_referral_redemptions_referred_user_id ON referral_redemptions (referred_user_id);
CREATE INDEX idx_referral_redemptions_booking_id       ON referral_redemptions (booking_id);
CREATE INDEX idx_referral_redemptions_reward_status    ON referral_redemptions (reward_status);

CREATE UNIQUE INDEX uk_referral_redemptions_booking_id
    ON referral_redemptions (booking_id)
    WHERE booking_id IS NOT NULL;


-- =====================================================================
-- 9. PAYMENTS  (depend on bookings)
-- =====================================================================

-- payments (entity: com.localbuddy.payment.Payment)
CREATE TABLE payments (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    booking_id                    UUID         NOT NULL,

    provider                      VARCHAR(40)  NOT NULL DEFAULT 'STRIPE',
    payment_method_type           VARCHAR(60)  NOT NULL DEFAULT 'UNKNOWN',
    payment_status                VARCHAR(40)  NOT NULL DEFAULT 'PENDING',

    amount                        NUMERIC(10, 2) NOT NULL,
    currency                      VARCHAR(3)   NOT NULL DEFAULT 'EUR',

    platform_fee_amount           NUMERIC(10, 2) NOT NULL DEFAULT 0,
    local_payout_amount           NUMERIC(10, 2) NOT NULL DEFAULT 0,

    provider_checkout_session_id  VARCHAR(255),
    provider_payment_intent_id    VARCHAR(255),
    provider_charge_id            VARCHAR(255),
    provider_customer_id          VARCHAR(255),
    provider_payment_method_id    VARCHAR(255),

    checkout_url                  TEXT,

    failure_reason                TEXT,
    refund_reason                 TEXT,
    provider_refund_id            VARCHAR(255),
    refunded_amount               NUMERIC(10, 2),

    paid_at                       TIMESTAMPTZ,
    failed_at                     TIMESTAMPTZ,
    cancelled_at                  TIMESTAMPTZ,
    refunded_at                   TIMESTAMPTZ,

    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_payments_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id),

    CONSTRAINT chk_payments_amount_non_negative        CHECK (amount >= 0),
    CONSTRAINT chk_payments_platform_fee_non_negative   CHECK (platform_fee_amount >= 0),
    CONSTRAINT chk_payments_local_payout_non_negative   CHECK (local_payout_amount >= 0),
    CONSTRAINT chk_payments_fee_plus_payout_valid       CHECK (platform_fee_amount + local_payout_amount <= amount)
);

CREATE UNIQUE INDEX ux_payments_booking_active
    ON payments (booking_id)
    WHERE payment_status IN ('PENDING', 'PROCESSING', 'PAID');

CREATE UNIQUE INDEX ux_payments_provider_checkout_session_id
    ON payments (provider, provider_checkout_session_id)
    WHERE provider_checkout_session_id IS NOT NULL;

CREATE UNIQUE INDEX ux_payments_provider_payment_intent_id
    ON payments (provider, provider_payment_intent_id)
    WHERE provider_payment_intent_id IS NOT NULL;

CREATE UNIQUE INDEX ux_payments_provider_charge_id
    ON payments (provider, provider_charge_id)
    WHERE provider_charge_id IS NOT NULL;

CREATE INDEX idx_payments_booking_id   ON payments (booking_id);
CREATE INDEX idx_payments_provider     ON payments (provider);
CREATE INDEX idx_payments_method_type  ON payments (payment_method_type);
CREATE INDEX idx_payments_status       ON payments (payment_status);
CREATE INDEX idx_payments_created_at   ON payments (created_at);
CREATE INDEX idx_payments_booking_status_created
    ON payments (booking_id, payment_status, created_at DESC);
CREATE INDEX idx_payments_provider_checkout_session
    ON payments (provider, provider_checkout_session_id);


-- payment_webhook_events (entity: com.localbuddy.payment.PaymentWebhookEvent)
CREATE TABLE payment_webhook_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    provider          VARCHAR(40)  NOT NULL DEFAULT 'STRIPE',
    provider_event_id VARCHAR(255) NOT NULL,
    event_type        VARCHAR(120) NOT NULL,

    processed         BOOLEAN      NOT NULL DEFAULT FALSE,
    processing_error  TEXT,

    received_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMPTZ,

    raw_payload       TEXT,

    CONSTRAINT ux_payment_webhook_provider_event
        UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payment_webhook_events_provider     ON payment_webhook_events (provider);
CREATE INDEX idx_payment_webhook_events_event_type   ON payment_webhook_events (event_type);
CREATE INDEX idx_payment_webhook_events_processed    ON payment_webhook_events (processed);
CREATE INDEX idx_payment_webhook_events_received_at  ON payment_webhook_events (received_at);
CREATE INDEX idx_payment_webhook_events_provider_event
    ON payment_webhook_events (provider, provider_event_id);


-- =====================================================================
-- 10. REVIEWS
-- =====================================================================

-- reviews (entity: com.localbuddy.review.Review)
-- ENTITY-WINS:
--   * direction, reviewee_user_id, moderation_reason, moderated_at exist on
--     the entity but were never added by a migration; included here.
--   * V10 declared booking_id UNIQUE; the entity maps it as a plain
--     @ManyToOne (no unique). Two-directional reviews (traveler->host and
--     host->traveler) require multiple rows per booking, so booking_id is
--     NOT unique here. (Hibernate validate does not check uniqueness, so
--     this is schema-safe; the UNIQUE was intentionally dropped.)
CREATE TABLE reviews (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    booking_id        UUID         NOT NULL,
    direction         VARCHAR(40)  NOT NULL DEFAULT 'TRAVELER_TO_HOST',
    reviewer_user_id  UUID,
    reviewee_user_id  UUID,
    local_profile_id  UUID         NOT NULL,
    experience_id     UUID         NOT NULL,

    rating            INTEGER      NOT NULL,
    comment           TEXT,

    status            VARCHAR(40)  NOT NULL DEFAULT 'VISIBLE',
    moderation_reason TEXT,
    moderated_at      TIMESTAMPTZ,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_reviews_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT fk_reviews_reviewer_user
        FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_reviewee_user
        FOREIGN KEY (reviewee_user_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id),
    CONSTRAINT fk_reviews_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id),

    CONSTRAINT chk_reviews_rating_range CHECK (rating >= 1 AND rating <= 5)
);

CREATE INDEX idx_reviews_local_profile_id ON reviews (local_profile_id);
CREATE INDEX idx_reviews_experience_id    ON reviews (experience_id);
CREATE INDEX idx_reviews_reviewer_user_id ON reviews (reviewer_user_id);
CREATE INDEX idx_reviews_status           ON reviews (status);
CREATE INDEX idx_reviews_created_at       ON reviews (created_at);


-- =====================================================================
-- 11. NOTIFICATIONS
-- =====================================================================

-- notifications (entity: com.localbuddy.notification.Notification)
-- ENTITY-WINS: read_at added by V32; present on the entity.
CREATE TABLE notifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    recipient_user_id   UUID,
    recipient_email     VARCHAR(255),
    recipient_phone     VARCHAR(40),

    channel             VARCHAR(40)  NOT NULL,
    notification_type   VARCHAR(80)  NOT NULL,

    subject             VARCHAR(255),
    message             TEXT         NOT NULL,

    status              VARCHAR(40)  NOT NULL DEFAULT 'PENDING',

    dedupe_key          VARCHAR(255) NOT NULL UNIQUE,

    related_entity_type VARCHAR(80),
    related_entity_id   UUID,

    provider_message_id VARCHAR(255),
    failure_reason      TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,

    CONSTRAINT fk_notifications_recipient_user
        FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT chk_notifications_recipient_exists
        CHECK (
            recipient_user_id IS NOT NULL
            OR recipient_email IS NOT NULL
            OR recipient_phone IS NOT NULL
        )
);

CREATE INDEX idx_notifications_recipient_user_id ON notifications (recipient_user_id);
CREATE INDEX idx_notifications_recipient_email   ON notifications (recipient_email);
CREATE INDEX idx_notifications_channel           ON notifications (channel);
CREATE INDEX idx_notifications_type              ON notifications (notification_type);
CREATE INDEX idx_notifications_status            ON notifications (status);
CREATE INDEX idx_notifications_related_entity    ON notifications (related_entity_type, related_entity_id);
CREATE INDEX idx_notifications_created_at        ON notifications (created_at);


-- =====================================================================
-- 12. CONSENTS  (entity-driven; user_consents from V14)
-- =====================================================================

-- user_consents (entity: com.localbuddy.consent.UserConsent)
CREATE TABLE user_consents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    consent_type VARCHAR(80)  NOT NULL,
    version      VARCHAR(80)  NOT NULL,
    accepted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ip_address   VARCHAR(120),
    user_agent   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_user_consents_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_user_consents_user_type_version
        UNIQUE (user_id, consent_type, version)
);

CREATE INDEX idx_user_consents_user_id      ON user_consents (user_id);
CREATE INDEX idx_user_consents_type_version ON user_consents (consent_type, version);
CREATE INDEX idx_user_consents_accepted_at  ON user_consents (accepted_at);


-- =====================================================================
-- 13. SAFETY (legacy) & TRUST & SAFETY (foundation)
-- =====================================================================

-- safety_reports (entity: com.localbuddy.safety.SafetyReport)
CREATE TABLE safety_reports (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    reporter_user_id UUID         NOT NULL,
    reported_user_id UUID,
    booking_id       UUID,

    report_type      VARCHAR(50)  NOT NULL,
    severity         VARCHAR(40)  NOT NULL DEFAULT 'MEDIUM',
    status           VARCHAR(40)  NOT NULL DEFAULT 'OPEN',

    description      TEXT         NOT NULL,
    admin_notes      TEXT,
    resolution_note  TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ,

    CONSTRAINT fk_safety_reports_reporter_user
        FOREIGN KEY (reporter_user_id) REFERENCES users (id),
    CONSTRAINT fk_safety_reports_reported_user
        FOREIGN KEY (reported_user_id) REFERENCES users (id),
    CONSTRAINT fk_safety_reports_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
);

CREATE INDEX idx_safety_reports_reporter_user_id ON safety_reports (reporter_user_id);
CREATE INDEX idx_safety_reports_reported_user_id ON safety_reports (reported_user_id);
CREATE INDEX idx_safety_reports_booking_id       ON safety_reports (booking_id);
CREATE INDEX idx_safety_reports_report_type      ON safety_reports (report_type);
CREATE INDEX idx_safety_reports_severity         ON safety_reports (severity);
CREATE INDEX idx_safety_reports_status           ON safety_reports (status);
CREATE INDEX idx_safety_reports_created_at       ON safety_reports (created_at);


-- booking_safety_checklists (entity: com.localbuddy.safety.BookingSafetyChecklist)
CREATE TABLE booking_safety_checklists (
    id                                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id                            UUID        NOT NULL,
    user_id                               UUID        NOT NULL,

    role_context                          VARCHAR(40) NOT NULL,

    public_meeting_acknowledged           BOOLEAN     NOT NULL DEFAULT FALSE,
    communication_guidelines_acknowledged BOOLEAN     NOT NULL DEFAULT FALSE,
    personal_safety_acknowledged          BOOLEAN     NOT NULL DEFAULT FALSE,
    reporting_guidelines_acknowledged     BOOLEAN     NOT NULL DEFAULT FALSE,

    completed                             BOOLEAN     NOT NULL DEFAULT FALSE,
    completed_at                          TIMESTAMPTZ,

    ip_address                            VARCHAR(120),
    user_agent                            TEXT,

    created_at                            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_booking_safety_checklists_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT fk_booking_safety_checklists_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_booking_safety_checklists_booking_user
        UNIQUE (booking_id, user_id)
);

CREATE INDEX idx_booking_safety_checklists_booking_id ON booking_safety_checklists (booking_id);
CREATE INDEX idx_booking_safety_checklists_user_id    ON booking_safety_checklists (user_id);
CREATE INDEX idx_booking_safety_checklists_completed  ON booking_safety_checklists (completed);


-- trust_safety_reports (entity: com.localbuddy.trustsafety.TrustSafetyReport)
CREATE TABLE trust_safety_reports (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    reporter_user_id UUID,
    reported_user_id UUID,
    booking_id       UUID,

    report_type      VARCHAR(60)  NOT NULL,
    severity         VARCHAR(40)  NOT NULL DEFAULT 'MEDIUM',
    status           VARCHAR(40)  NOT NULL DEFAULT 'OPEN',

    description      TEXT         NOT NULL,
    admin_notes      TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ,

    CONSTRAINT fk_trust_safety_reports_reporter_user
        FOREIGN KEY (reporter_user_id) REFERENCES users (id),
    CONSTRAINT fk_trust_safety_reports_reported_user
        FOREIGN KEY (reported_user_id) REFERENCES users (id),
    CONSTRAINT fk_trust_safety_reports_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
);

CREATE INDEX idx_trust_safety_reports_reporter_user_id ON trust_safety_reports (reporter_user_id);
CREATE INDEX idx_trust_safety_reports_reported_user_id ON trust_safety_reports (reported_user_id);
CREATE INDEX idx_trust_safety_reports_booking_id       ON trust_safety_reports (booking_id);
CREATE INDEX idx_trust_safety_reports_status           ON trust_safety_reports (status);
CREATE INDEX idx_trust_safety_reports_severity         ON trust_safety_reports (severity);


-- user_account_restrictions (entity: com.localbuddy.trustsafety.UserAccountRestriction)
CREATE TABLE user_account_restrictions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id                  UUID         NOT NULL,
    restriction_type         VARCHAR(60)  NOT NULL,

    reason                   TEXT         NOT NULL,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,

    created_by_admin_user_id UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deactivated_at           TIMESTAMPTZ,

    CONSTRAINT fk_user_account_restrictions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_account_restrictions_admin_user
        FOREIGN KEY (created_by_admin_user_id) REFERENCES users (id)
);

CREATE INDEX idx_user_account_restrictions_user_id ON user_account_restrictions (user_id);
CREATE INDEX idx_user_account_restrictions_type    ON user_account_restrictions (restriction_type);
CREATE INDEX idx_user_account_restrictions_active  ON user_account_restrictions (active);
CREATE INDEX idx_user_account_restrictions_user_type_active
    ON user_account_restrictions (user_id, restriction_type, active);


-- =====================================================================
-- 14. WAITLIST
-- =====================================================================

-- slot_waitlist_entries (entity: com.localbuddy.waitlist.WaitlistEntry)
CREATE TABLE slot_waitlist_entries (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    availability_slot_id UUID         NOT NULL,
    experience_id        UUID         NOT NULL,

    user_id              UUID,
    guest_name           VARCHAR(150),
    guest_email          VARCHAR(255),
    guest_phone          VARCHAR(40),

    guests_count         INTEGER      NOT NULL DEFAULT 1,
    status               VARCHAR(40)  NOT NULL DEFAULT 'WAITING',

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    notified_at          TIMESTAMPTZ,

    CONSTRAINT fk_waitlist_slot
        FOREIGN KEY (availability_slot_id) REFERENCES availability_slots (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_user
        FOREIGN KEY (user_id) REFERENCES users (id),

    CONSTRAINT chk_waitlist_user_or_guest
        CHECK (
            user_id IS NOT NULL
            OR (guest_name IS NOT NULL AND guest_email IS NOT NULL AND guest_phone IS NOT NULL)
        ),
    CONSTRAINT chk_waitlist_guests_positive CHECK (guests_count > 0)
);

CREATE INDEX idx_waitlist_slot_status ON slot_waitlist_entries (availability_slot_id, status);
CREATE INDEX idx_waitlist_user        ON slot_waitlist_entries (user_id);
CREATE INDEX idx_waitlist_guest_email ON slot_waitlist_entries (LOWER(guest_email));

CREATE UNIQUE INDEX ux_waitlist_active_user_slot
    ON slot_waitlist_entries (user_id, availability_slot_id)
    WHERE user_id IS NOT NULL AND status IN ('WAITING', 'NOTIFIED');

CREATE UNIQUE INDEX ux_waitlist_active_guest_slot
    ON slot_waitlist_entries (LOWER(guest_email), availability_slot_id)
    WHERE user_id IS NULL AND status IN ('WAITING', 'NOTIFIED');


-- =====================================================================
-- 15. MESSAGING  (entity-driven; NO historical migration)
-- =====================================================================

-- conversations (entity: com.localbuddy.messaging.Conversation)
-- ENTITY-WINS: no migration exists; created from the entity mapping.
CREATE TABLE conversations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    traveler_user_id UUID        NOT NULL,
    host_user_id     UUID        NOT NULL,
    experience_id    UUID,
    booking_id       UUID,

    last_message_at  TIMESTAMPTZ,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_conversations_traveler_user
        FOREIGN KEY (traveler_user_id) REFERENCES users (id),
    CONSTRAINT fk_conversations_host_user
        FOREIGN KEY (host_user_id) REFERENCES users (id),
    CONSTRAINT fk_conversations_experience
        FOREIGN KEY (experience_id) REFERENCES experiences (id),
    CONSTRAINT fk_conversations_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
);

CREATE INDEX idx_conversations_traveler_user_id ON conversations (traveler_user_id);
CREATE INDEX idx_conversations_host_user_id     ON conversations (host_user_id);
CREATE INDEX idx_conversations_experience_id    ON conversations (experience_id);
CREATE INDEX idx_conversations_booking_id       ON conversations (booking_id);
CREATE INDEX idx_conversations_last_message_at  ON conversations (last_message_at);


-- messages (entity: com.localbuddy.messaging.Message)
-- ENTITY-WINS: no migration exists; created from the entity mapping.
-- NOTE: the Message entity has no updated_at column (only created_at, read_at).
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    conversation_id UUID        NOT NULL,
    sender_user_id  UUID        NOT NULL,

    body            TEXT        NOT NULL,
    read_at         TIMESTAMPTZ,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender_user
        FOREIGN KEY (sender_user_id) REFERENCES users (id)
);

CREATE INDEX idx_messages_conversation_id ON messages (conversation_id);
CREATE INDEX idx_messages_sender_user_id  ON messages (sender_user_id);
CREATE INDEX idx_messages_created_at      ON messages (created_at);


-- =====================================================================
-- 16. DEALS  (entity-driven; NO historical migration)
-- =====================================================================

-- deals (entity: com.localbuddy.deals.Deal)
-- ENTITY-WINS: no migration exists; created from the entity mapping.
-- NOTE: target_city_id / target_experience_id / target_category_id are plain
-- UUID columns on the entity (NOT @ManyToOne) — no FK constraints declared.
CREATE TABLE deals (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name                 VARCHAR(200) NOT NULL,
    description          TEXT,

    deal_type            VARCHAR(40)  NOT NULL,
    discount_type        VARCHAR(40)  NOT NULL,
    discount_value       NUMERIC(10, 2) NOT NULL,
    currency             VARCHAR(10),

    scope                VARCHAR(40)  NOT NULL,
    target_city_id       UUID,
    target_experience_id UUID,
    target_category_id   UUID,

    starts_at            TIMESTAMPTZ,
    ends_at              TIMESTAMPTZ,

    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    priority             INTEGER      NOT NULL DEFAULT 0,
    badge_text           VARCHAR(80),

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deals_active   ON deals (active);
CREATE INDEX idx_deals_scope    ON deals (scope);
CREATE INDEX idx_deals_priority ON deals (priority);


-- =====================================================================
-- 17. JOIN TABLES — host profile multi-selects  (V28)
-- =====================================================================

-- local_profile_experience_cities (entity: LocalProfile.experienceCities)
CREATE TABLE local_profile_experience_cities (
    local_profile_id UUID NOT NULL,
    city_id          UUID NOT NULL,
    PRIMARY KEY (local_profile_id, city_id),
    CONSTRAINT fk_lpec_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_lpec_city
        FOREIGN KEY (city_id) REFERENCES cities (id)
);

CREATE INDEX idx_lpec_city_id ON local_profile_experience_cities (city_id);


-- local_profile_experience_categories (entity: LocalProfile.experienceCategories)
CREATE TABLE local_profile_experience_categories (
    local_profile_id UUID NOT NULL,
    category_id      UUID NOT NULL,
    PRIMARY KEY (local_profile_id, category_id),
    CONSTRAINT fk_lpecat_local_profile
        FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id) ON DELETE CASCADE,
    CONSTRAINT fk_lpecat_category
        FOREIGN KEY (category_id) REFERENCES experience_categories (id)
);

CREATE INDEX idx_lpecat_category_id ON local_profile_experience_categories (category_id);


-- =====================================================================
-- 18. SEED / REFERENCE DATA  (idempotent)
-- =====================================================================

-- Experience categories (final form, V4)
INSERT INTO experience_categories (name, slug, description, active, display_order)
VALUES
    ('Food',          'food',          'Local food spots, markets, cafes, and casual food walks.',                 TRUE, 10),
    ('Photo Walk',    'photo-walk',    'Photo-friendly local walks with scenic or hidden spots.',                  TRUE, 20),
    ('Hidden Gems',   'hidden-gems',   'Local places that are not usually part of tourist routes.',                TRUE, 30),
    ('Local Markets', 'local-markets', 'Neighborhood markets, street food, and local shopping areas.',             TRUE, 40),
    ('Cafe Hopping',  'cafe-hopping',  'Local cafes for coffee, working, relaxing, or socializing.',               TRUE, 50),
    ('Student Life',  'student-life',  'Local student hangouts, budget spots, and university-area experiences.',   TRUE, 60),
    ('Nightlife',     'nightlife',     'Local bars, evening hangouts, and safe nightlife discovery.',              TRUE, 70),
    ('Custom',        'custom',        'Flexible local experience customized for the traveler.',                   TRUE, 100)
ON CONFLICT (slug) DO NOTHING;


-- Launch city (V27)
INSERT INTO cities (name, slug, country, active, display_order)
VALUES ('Amsterdam', 'amsterdam', 'Netherlands', TRUE, 10)
ON CONFLICT (slug) DO NOTHING;


-- Cancellation refund policies (V22)
INSERT INTO cancellation_refund_policies (
    id, name, cancelled_by, min_hours_before_start, max_hours_before_start,
    refund_percentage, active, created_at, updated_at
)
VALUES
    ('11111111-1111-1111-1111-111111111101', 'Traveler cancellation - 24+ hours before start',          'TRAVELER', 24.00, NULL,  100.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111102', 'Traveler cancellation - 12 to 24 hours before start',     'TRAVELER', 12.00, 24.00,  50.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111103', 'Traveler cancellation - 2 to 12 hours before start',      'TRAVELER',  2.00, 12.00,  25.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111104', 'Traveler cancellation - less than 2 hours before start',  'TRAVELER',  0.00,  2.00,   0.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111201', 'Local cancellation - full refund',                        'LOCAL',     0.00, NULL,  100.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111301', 'Admin cancellation - full refund',                        'ADMIN',     0.00, NULL,  100.00, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- =====================================================================
-- END OF BASELINE
-- =====================================================================
