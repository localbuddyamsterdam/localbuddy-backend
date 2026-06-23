-- No-show tracking.
--
-- attendance_outcome flags a booking once an admin verifies a no-show report; it is separate
-- from the booking status so a booking records WHO failed to show independently of its lifecycle.

ALTER TABLE bookings ADD COLUMN attendance_outcome VARCHAR(30) NOT NULL DEFAULT 'NONE';
ALTER TABLE bookings ADD COLUMN no_show_marked_at   TIMESTAMPTZ;

CREATE TABLE no_show_reports (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id           UUID         NOT NULL,
    reported_by_user_id  UUID,
    subject              VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    reason               TEXT,
    admin_note           TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at          TIMESTAMPTZ,

    CONSTRAINT fk_no_show_reports_booking FOREIGN KEY (booking_id)          REFERENCES bookings (id),
    CONSTRAINT fk_no_show_reports_user    FOREIGN KEY (reported_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_no_show_reports_status  ON no_show_reports (status);
CREATE INDEX idx_no_show_reports_booking ON no_show_reports (booking_id);
