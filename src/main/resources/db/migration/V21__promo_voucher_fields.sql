-- Voucher support on promo codes: who bears the discount cost + optional per-customer targeting.
ALTER TABLE promo_codes
    ADD COLUMN discount_bearer            VARCHAR(20)   NOT NULL DEFAULT 'HOST',
    ADD COLUMN platform_share_percentage  NUMERIC(5, 2),
    ADD COLUMN issued_to_user_id          UUID,
    ADD COLUMN issued_to_email            VARCHAR(255),
    ADD COLUMN combinable                 BOOLEAN       NOT NULL DEFAULT FALSE;

ALTER TABLE promo_codes
    ADD CONSTRAINT fk_promo_codes_issued_to_user
        FOREIGN KEY (issued_to_user_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE promo_codes
    ADD CONSTRAINT chk_promo_platform_share
        CHECK (platform_share_percentage IS NULL
               OR (platform_share_percentage >= 0 AND platform_share_percentage <= 100));

CREATE INDEX idx_promo_codes_issued_to_user ON promo_codes (issued_to_user_id);
