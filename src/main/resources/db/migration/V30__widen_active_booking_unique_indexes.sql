-- Widen the "one active booking per traveler/guest per slot" guard.
-- Bookings jump straight to PENDING_PAYMENT, but the original indexes (V8/V9)
-- only covered REQUESTED/ACCEPTED, so the database was not actually enforcing
-- the rule for the statuses bookings spend most of their life in. This closes
-- the check-then-insert race by letting the DB enforce uniqueness across all
-- active statuses.

-- Fail loudly if existing data already violates the wider rule.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM bookings
        WHERE traveler_user_id IS NOT NULL
          AND status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED')
        GROUP BY traveler_user_id, availability_slot_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate active traveler bookings exist; resolve before widening unique index';
    END IF;

    IF EXISTS (
        SELECT 1 FROM bookings
        WHERE traveler_user_id IS NULL
          AND status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED')
        GROUP BY LOWER(guest_email), availability_slot_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate active guest bookings exist; resolve before widening unique index';
    END IF;
END $$;

DROP INDEX IF EXISTS ux_bookings_active_traveler_slot;
CREATE UNIQUE INDEX ux_bookings_active_traveler_slot
    ON bookings (traveler_user_id, availability_slot_id)
    WHERE status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED');

DROP INDEX IF EXISTS ux_bookings_active_guest_slot;
CREATE UNIQUE INDEX ux_bookings_active_guest_slot
    ON bookings (LOWER(guest_email), availability_slot_id)
    WHERE traveler_user_id IS NULL
      AND status IN ('REQUESTED', 'ACCEPTED', 'PENDING_PAYMENT', 'CONFIRMED');
