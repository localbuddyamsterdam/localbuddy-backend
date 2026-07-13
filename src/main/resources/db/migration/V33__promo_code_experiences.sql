-- Scope a promo code to specific experiences. No rows for a code = applies to all
-- experiences (platform-wide, the existing behaviour).
CREATE TABLE promo_code_experiences (
    promo_code_id UUID NOT NULL REFERENCES promo_codes(id) ON DELETE CASCADE,
    experience_id UUID NOT NULL,
    PRIMARY KEY (promo_code_id, experience_id)
);

CREATE INDEX idx_promo_code_experiences_promo ON promo_code_experiences (promo_code_id);
