-- Admin SOS incident lifecycle on top of the existing `resolved` flag: acknowledge
-- (a responder has eyes on it), escalate (bumped to on-call), and a free-text
-- resolution note. All nullable; check-in/out events and existing rows leave them empty.
ALTER TABLE trip_safety_events
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_by UUID,
    ADD COLUMN escalated_at    TIMESTAMPTZ,
    ADD COLUMN resolution_note TEXT;
