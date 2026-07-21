-- Per-host and per-experience discount bearer policy:
-- Controls who funds/bears the cost of discounts (promos, deals) on their experiences
ALTER TABLE local_profiles
    ADD COLUMN default_discount_bearer            VARCHAR(20)   NOT NULL DEFAULT 'HOST',
    ADD COLUMN default_platform_share_percentage  NUMERIC(5, 2);

ALTER TABLE experiences
    ADD COLUMN discount_bearer            VARCHAR(20),
    ADD COLUMN platform_share_percentage  NUMERIC(5, 2);

-- Constraints: discount_bearer must be a valid bearer type; percentage must be 0-100 when set
ALTER TABLE local_profiles
    ADD CONSTRAINT chk_host_discount_bearer
        CHECK (default_discount_bearer IN ('HOST', 'PLATFORM', 'SPLIT')),
    ADD CONSTRAINT chk_host_platform_share
        CHECK (default_platform_share_percentage IS NULL
               OR (default_platform_share_percentage >= 0 AND default_platform_share_percentage <= 100));

ALTER TABLE experiences
    ADD CONSTRAINT chk_exp_discount_bearer
        CHECK (discount_bearer IS NULL OR discount_bearer IN ('HOST', 'PLATFORM', 'SPLIT')),
    ADD CONSTRAINT chk_exp_platform_share
        CHECK (platform_share_percentage IS NULL
               OR (platform_share_percentage >= 0 AND platform_share_percentage <= 100));

-- Index for lookups during promo/deal application
CREATE INDEX idx_local_profiles_discount_bearer ON local_profiles (default_discount_bearer);
CREATE INDEX idx_experiences_discount_bearer ON experiences (discount_bearer);
