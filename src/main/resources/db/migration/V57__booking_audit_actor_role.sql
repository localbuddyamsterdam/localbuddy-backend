-- Booking change audit gains an actor role so the timeline can say WHO made each change
-- (the traveller themselves, the host, an admin/support agent, a guest, or the system) rather
-- than only "an admin". Existing rows were all written by the admin console, so backfill ADMIN.
ALTER TABLE booking_change_audit
    ADD COLUMN actor_role VARCHAR(20);

UPDATE booking_change_audit SET actor_role = 'ADMIN' WHERE actor_role IS NULL;
