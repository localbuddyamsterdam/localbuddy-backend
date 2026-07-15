-- Emergency contact fields.
--
-- 1) Upgrade the profile emergency contact (emergency_contacts) from a single
--    combined name to first/last name + an optional email, so it matches the
--    richer shape captured at checkout (lossless prefill and save-back).
-- 2) Capture a point-in-time emergency-contact snapshot on each booking. The
--    contact is optional at checkout, so every booking column is nullable; the
--    "all-or-nothing" rule is enforced in the application/service layer.

-- ---------------------------------------------------------------------------
-- 1) Profile emergency contact: split name, add optional email.
-- ---------------------------------------------------------------------------
ALTER TABLE emergency_contacts ADD COLUMN first_name VARCHAR(100);
ALTER TABLE emergency_contacts ADD COLUMN last_name  VARCHAR(100);
ALTER TABLE emergency_contacts ADD COLUMN email      VARCHAR(255);

-- Backfill first/last from the existing combined name: first whitespace token is
-- the first name, the remainder is the last name (empty when the name is single-word).
-- left(...,100) guards against the source contact_name (VARCHAR 150) overflowing the
-- narrower VARCHAR(100) targets, which would otherwise abort the migration.
UPDATE emergency_contacts
SET first_name = left(COALESCE(NULLIF(split_part(btrim(contact_name), ' ', 1), ''), btrim(contact_name)), 100),
    last_name  = left(CASE
                     WHEN position(' ' IN btrim(contact_name)) > 0
                         THEN btrim(substring(btrim(contact_name) FROM position(' ' IN btrim(contact_name)) + 1))
                     ELSE ''
                 END, 100)
WHERE contact_name IS NOT NULL;

ALTER TABLE emergency_contacts ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE emergency_contacts ALTER COLUMN last_name  SET NOT NULL;

-- Retire the old combined-name column (fully replaced by first_name + last_name).
ALTER TABLE emergency_contacts DROP COLUMN contact_name;

-- ---------------------------------------------------------------------------
-- 2) Booking emergency-contact snapshot (all nullable — optional at checkout).
-- ---------------------------------------------------------------------------
ALTER TABLE bookings ADD COLUMN emergency_contact_first_name   VARCHAR(100);
ALTER TABLE bookings ADD COLUMN emergency_contact_last_name    VARCHAR(100);
ALTER TABLE bookings ADD COLUMN emergency_contact_email        VARCHAR(255);
ALTER TABLE bookings ADD COLUMN emergency_contact_phone        VARCHAR(40);
ALTER TABLE bookings ADD COLUMN emergency_contact_relationship VARCHAR(80);
