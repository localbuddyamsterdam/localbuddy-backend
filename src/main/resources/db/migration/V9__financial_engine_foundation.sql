-- =====================================================================
-- Financial engine foundation
-- Configurable commission + customer service fee, per-leg VAT (place of
-- supply x category x date, reverse-charge / KOR), company/BTW details,
-- host tax info (DAC7), per-transaction snapshots, and rate-change audit.
-- All money NUMERIC(_,2), all rates NUMERIC(5,4) (0.2100 = 21%).
-- =====================================================================

-- 1. Company / platform legal + BTW details (admin-editable; one active row) --
CREATE TABLE company_settings (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    legal_name            VARCHAR(200) NOT NULL,
    trading_name          VARCHAR(200),
    vat_number            VARCHAR(40),                 -- your BTW number
    coc_number            VARCHAR(40),                 -- KvK / chamber of commerce
    address_line1         VARCHAR(200),
    address_line2         VARCHAR(200),
    postal_code           VARCHAR(20),
    city                  VARCHAR(100),
    country               VARCHAR(2)  NOT NULL DEFAULT 'NL',
    email                 VARCHAR(255),
    phone                 VARCHAR(40),
    iban                  VARCHAR(64),
    invoice_number_prefix VARCHAR(20) NOT NULL DEFAULT 'LB',
    invoice_footer        TEXT,
    is_active             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Placeholder row so invoices have an issuer; edit via admin before go-live.
INSERT INTO company_settings (legal_name, country, invoice_number_prefix)
VALUES ('LocalBuddy (configure in admin)', 'NL', 'LB');

-- 2. Commission rules (scoped + time-boxed) -----------------------------------
-- Resolution precedence: EXPERIENCE > HOST > CATEGORY > CITY > PLATFORM,
-- within scope the active rule whose [effective_from, effective_to) covers now.
CREATE TABLE commission_rules (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type         VARCHAR(20)  NOT NULL,          -- PLATFORM|CITY|CATEGORY|HOST|EXPERIENCE
    scope_id           UUID,                           -- null for PLATFORM
    rate               NUMERIC(5, 4) NOT NULL,
    effective_from     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    effective_to       TIMESTAMPTZ,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    note               TEXT,
    created_by_user_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_commission_rate CHECK (rate >= 0 AND rate <= 1)
);
CREATE INDEX idx_commission_rules_scope ON commission_rules (scope_type, scope_id, active);

-- Default platform commission 20%.
INSERT INTO commission_rules (scope_type, rate, note)
VALUES ('PLATFORM', 0.2000, 'Default platform commission');

-- 3. Customer service-fee rules (same scoping; default 2.5%) -------------------
CREATE TABLE service_fee_rules (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_type         VARCHAR(20)  NOT NULL,
    scope_id           UUID,
    rate               NUMERIC(5, 4) NOT NULL,
    effective_from     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    effective_to       TIMESTAMPTZ,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    note               TEXT,
    created_by_user_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_service_fee_rate CHECK (rate >= 0 AND rate <= 1)
);
CREATE INDEX idx_service_fee_rules_scope ON service_fee_rules (scope_type, scope_id, active);

INSERT INTO service_fee_rules (scope_type, rate, note)
VALUES ('PLATFORM', 0.0250, 'Default customer service fee');

-- 4. VAT rates by jurisdiction x category x date ------------------------------
CREATE TABLE vat_rates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country        VARCHAR(2)   NOT NULL,              -- place of supply
    category_id    UUID,                               -- experience_categories.id; null = country default
    rate           NUMERIC(5, 4) NOT NULL,
    rate_kind      VARCHAR(20)  NOT NULL DEFAULT 'STANDARD', -- STANDARD|REDUCED|ZERO
    description    VARCHAR(120),
    effective_from TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    effective_to   TIMESTAMPTZ,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_vat_rate CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT fk_vat_rates_category
        FOREIGN KEY (category_id) REFERENCES experience_categories (id)
);
CREATE INDEX idx_vat_rates_lookup ON vat_rates (country, category_id, active);

-- NL defaults: standard 21% + reduced 9% (assign categories later via admin).
INSERT INTO vat_rates (country, rate, rate_kind, description)
VALUES ('NL', 0.2100, 'STANDARD', 'Netherlands standard BTW'),
       ('NL', 0.0900, 'REDUCED',  'Netherlands reduced BTW (culture/recreation)');

-- 5. Rate-change audit (commission / service-fee / VAT) -----------------------
CREATE TABLE rate_change_audit (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_type          VARCHAR(20) NOT NULL,           -- COMMISSION|SERVICE_FEE|VAT
    rule_id            UUID,
    old_value          JSONB,
    new_value          JSONB,
    changed_by_user_id UUID,
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rate_change_audit_type ON rate_change_audit (rate_type, changed_at);

-- 6. Host tax / VAT / DAC7 fields + per-host commission override --------------
ALTER TABLE local_profiles
    ADD COLUMN vat_registered               BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN vat_number                   VARCHAR(40),
    ADD COLUMN tax_country                  VARCHAR(2),     -- country of tax residence
    ADD COLUMN legal_entity_type            VARCHAR(20),    -- INDIVIDUAL|BUSINESS
    ADD COLUMN tax_identification_number    VARCHAR(60),    -- DAC7 TIN
    ADD COLUMN business_registration_number VARCHAR(60),
    ADD COLUMN date_of_birth                DATE,           -- DAC7 (individuals)
    ADD COLUMN commission_rate              NUMERIC(5, 4);  -- optional per-host override
ALTER TABLE local_profiles
    ADD CONSTRAINT chk_lp_commission_rate
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1));

-- 7. Experience: commission override, VAT category, net/gross input ----------
ALTER TABLE experiences
    ADD COLUMN commission_rate  NUMERIC(5, 4),                 -- optional, supersedes host
    ADD COLUMN price_input_mode VARCHAR(10) NOT NULL DEFAULT 'GROSS', -- NET|GROSS (which the host typed)
    ADD COLUMN price_net_amount NUMERIC(10, 2),                -- computed counterpart of price_amount
    ADD COLUMN vat_category     VARCHAR(30);                   -- optional override of category->VAT
ALTER TABLE experiences
    ADD CONSTRAINT chk_exp_commission_rate
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1));

-- 8. Payment financial snapshot (immutable per-transaction breakdown) ---------
ALTER TABLE payments
    ADD COLUMN commission_rate          NUMERIC(5, 4),
    ADD COLUMN commission_amount        NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN commission_vat_rate      NUMERIC(5, 4),
    ADD COLUMN commission_vat_amount    NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN commission_vat_treatment VARCHAR(20),    -- STANDARD|REVERSE_CHARGE|NOT_REGISTERED|OUT_OF_SCOPE
    ADD COLUMN service_fee_amount       NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN service_fee_vat_amount   NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN experience_gross_amount  NUMERIC(10, 2) NOT NULL DEFAULT 0, -- customer pays for experience
    ADD COLUMN experience_net_amount    NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN experience_vat_rate      NUMERIC(5, 4),
    ADD COLUMN experience_vat_amount    NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN place_of_supply_country  VARCHAR(2),
    ADD COLUMN host_payout_amount       NUMERIC(10, 2) NOT NULL DEFAULT 0; -- cash to transfer to host
