-- Read-state for in-app notifications. IN_APP notifications are now persisted as
-- readable records that the recipient fetches via the notification feed, so we
-- track when each one was read.

ALTER TABLE notifications
    ADD COLUMN read_at TIMESTAMPTZ;
