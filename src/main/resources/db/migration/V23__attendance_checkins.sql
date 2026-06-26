-- Geo check-in / arrival attendance.
--
-- attendance_check_ins records a host or guest checking in near the experience meeting point around
-- start time. Host check-ins are slot-level (booking_id NULL); guest check-ins are booking-level.
-- Operational + a soft signal — NOT an auto-refund decision. Distance is to the experience coordinate.
--
-- guest_show_status is the host's separate in-person mark (one per booking), distinct from the
-- admin-verified attendance_outcome.

ALTER TABLE bookings ADD COLUMN guest_show_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE bookings ADD COLUMN guest_show_marked_at TIMESTAMPTZ;

CREATE TABLE attendance_check_ins (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    availability_slot_id UUID         NOT NULL,
    booking_id           UUID,                          -- NULL for a host (slot-level) check-in
    user_id              UUID,                          -- NULL for an anonymous guest
    guest_email          VARCHAR(255),
    role                 VARCHAR(20)  NOT NULL,         -- HOST | GUEST
    checked_in_at        TIMESTAMPTZ  NOT NULL,         -- server clock, not the device
    latitude             NUMERIC(9,6),
    longitude            NUMERIC(9,6),
    accuracy_meters      DOUBLE PRECISION,
    distance_meters      DOUBLE PRECISION,              -- NULL if experience has no coordinate
    within_geofence      BOOLEAN      NOT NULL DEFAULT FALSE,
    photo_url            VARCHAR(2000),                 -- optional host arrival photo
    photo_storage_key    VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_attendance_check_ins_slot    FOREIGN KEY (availability_slot_id) REFERENCES availability_slots (id),
    CONSTRAINT fk_attendance_check_ins_booking FOREIGN KEY (booking_id)           REFERENCES bookings (id),
    CONSTRAINT fk_attendance_check_ins_user    FOREIGN KEY (user_id)              REFERENCES users (id)
);

-- One host check-in per slot, and one guest check-in per booking (upserted in service).
CREATE UNIQUE INDEX uq_attendance_host_per_slot
    ON attendance_check_ins (availability_slot_id)
    WHERE role = 'HOST';

CREATE UNIQUE INDEX uq_attendance_guest_per_booking
    ON attendance_check_ins (booking_id)
    WHERE role = 'GUEST' AND booking_id IS NOT NULL;

CREATE INDEX idx_attendance_check_ins_slot    ON attendance_check_ins (availability_slot_id);
CREATE INDEX idx_attendance_check_ins_booking ON attendance_check_ins (booking_id);
CREATE INDEX idx_attendance_check_ins_created ON attendance_check_ins (created_at);
