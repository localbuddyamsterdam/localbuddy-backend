-- =====================================================================
-- Booking change audit — records which admin performed each management
-- action on a booking (create, cancel, edit-details, party, reschedule,
-- attendance, complete) so the admin "Manage booking" History tab can
-- show who changed what and when. Mirrors rate_change_audit (V9).
-- =====================================================================
CREATE TABLE booking_change_audit (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         UUID        NOT NULL,
    action             VARCHAR(40) NOT NULL,           -- CREATE|CANCEL|EDIT_DETAILS|PARTY|RESCHEDULE|ATTENDANCE|COMPLETE
    detail             TEXT,                            -- human-readable summary
    changed_by_user_id UUID,                            -- the acting admin (users.id); null if unknown
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_booking_change_audit_booking
    ON booking_change_audit (booking_id, changed_at DESC);
