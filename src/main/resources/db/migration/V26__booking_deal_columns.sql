-- Deal applied to a booking (nullable) and the deal discount already reflected
-- in total_amount. Additive + safe for existing rows (default 0).
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS deal_id UUID;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS deal_discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;
