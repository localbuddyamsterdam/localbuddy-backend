-- Private (buyout) bookings.
-- A single party can reserve an ENTIRE availability slot ("private tour"). This
-- blocks every seat on the slot and applies a 20% discount on the full-slot
-- price (capacity x price_per_guest). Only allowed when the slot has no other
-- bookings yet. Promo/referral discounts still apply on top of the private base.

-- Whether this booking is a private buyout of the whole slot.
ALTER TABLE bookings
    ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;

-- The 20% private-buyout discount applied to the full-slot price.
ALTER TABLE bookings
    ADD COLUMN private_discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;

-- Number of slot seats this booking consumes. For normal bookings this equals
-- guests_count; for a private buyout it equals the slot capacity. All capacity
-- bookkeeping (block/release) uses this value.
ALTER TABLE bookings
    ADD COLUMN seats_blocked INTEGER;

-- Backfill existing rows: they each consumed guests_count seats.
UPDATE bookings
SET seats_blocked = guests_count
WHERE seats_blocked IS NULL;

ALTER TABLE bookings
    ALTER COLUMN seats_blocked SET NOT NULL;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_private_discount_non_negative
        CHECK (private_discount_amount >= 0);

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_seats_blocked_positive
        CHECK (seats_blocked >= 1);
