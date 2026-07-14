-- Split the single "full name" fields into first name + last name (+ optional preferred
-- name on accounts). Existing values are backfilled by splitting on the first space:
-- everything before it becomes the first name, the remainder the last name; a single-token
-- value seeds both. initcap() title-cases the backfilled values to match the new input rule.
--
-- Covered here: users (account name + optional preferred name), bookings (guest name) and
-- slot_waitlist_entries (guest name). The two guest CHECK constraints are rewritten to
-- require first + last (instead of the old single name) alongside email + phone.

-- ---------------------------------------------------------------- users
ALTER TABLE users ADD COLUMN first_name     VARCHAR(100);
ALTER TABLE users ADD COLUMN last_name      VARCHAR(100);
ALTER TABLE users ADD COLUMN preferred_name VARCHAR(100);

UPDATE users
SET first_name = COALESCE(NULLIF(initcap(trim(split_part(full_name, ' ', 1))), ''), 'User'),
    last_name  = COALESCE(
                     NULLIF(initcap(trim(substring(full_name FROM position(' ' IN full_name) + 1))), ''),
                     NULLIF(initcap(trim(split_part(full_name, ' ', 1))), ''),
                     'User');

ALTER TABLE users ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE users ALTER COLUMN last_name  SET NOT NULL;

ALTER TABLE users DROP COLUMN full_name;

-- ---------------------------------------------------------------- bookings (guest name)
ALTER TABLE bookings ADD COLUMN guest_first_name VARCHAR(100);
ALTER TABLE bookings ADD COLUMN guest_last_name  VARCHAR(100);

UPDATE bookings
SET guest_first_name = COALESCE(NULLIF(initcap(trim(split_part(guest_name, ' ', 1))), ''), guest_name),
    guest_last_name  = COALESCE(
                           NULLIF(initcap(trim(substring(guest_name FROM position(' ' IN guest_name) + 1))), ''),
                           NULLIF(initcap(trim(split_part(guest_name, ' ', 1))), ''),
                           guest_name)
WHERE guest_name IS NOT NULL;

ALTER TABLE bookings DROP CONSTRAINT chk_bookings_traveler_or_guest;
ALTER TABLE bookings ADD CONSTRAINT chk_bookings_traveler_or_guest
    CHECK (
        traveler_user_id IS NOT NULL
        OR (guest_first_name IS NOT NULL AND guest_last_name IS NOT NULL
            AND guest_email IS NOT NULL AND guest_phone IS NOT NULL)
    );

ALTER TABLE bookings DROP COLUMN guest_name;

-- ---------------------------------------------------------------- slot_waitlist_entries (guest name)
ALTER TABLE slot_waitlist_entries ADD COLUMN guest_first_name VARCHAR(100);
ALTER TABLE slot_waitlist_entries ADD COLUMN guest_last_name  VARCHAR(100);

UPDATE slot_waitlist_entries
SET guest_first_name = COALESCE(NULLIF(initcap(trim(split_part(guest_name, ' ', 1))), ''), guest_name),
    guest_last_name  = COALESCE(
                           NULLIF(initcap(trim(substring(guest_name FROM position(' ' IN guest_name) + 1))), ''),
                           NULLIF(initcap(trim(split_part(guest_name, ' ', 1))), ''),
                           guest_name)
WHERE guest_name IS NOT NULL;

ALTER TABLE slot_waitlist_entries DROP CONSTRAINT chk_waitlist_user_or_guest;
ALTER TABLE slot_waitlist_entries ADD CONSTRAINT chk_waitlist_user_or_guest
    CHECK (
        user_id IS NOT NULL
        OR (guest_first_name IS NOT NULL AND guest_last_name IS NOT NULL
            AND guest_email IS NOT NULL AND guest_phone IS NOT NULL)
    );

ALTER TABLE slot_waitlist_entries DROP COLUMN guest_name;
