-- A booking can have multiple stacked promo/voucher codes applied (only when all are combinable).
CREATE TABLE booking_promo_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID           NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    promo_code_id   UUID           NOT NULL REFERENCES promo_codes (id),
    code_text       VARCHAR(80),
    discount_amount NUMERIC(10, 2) NOT NULL CHECK (discount_amount >= 0),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_promo_codes_booking ON booking_promo_codes (booking_id);
CREATE INDEX idx_booking_promo_codes_promo   ON booking_promo_codes (promo_code_id);
