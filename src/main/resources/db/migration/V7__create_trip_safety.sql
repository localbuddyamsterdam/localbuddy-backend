-- Live-trip safety: per-user emergency contact + per-booking safety events (check-in/out, SOS).
CREATE TABLE emergency_contacts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    contact_name  VARCHAR(150) NOT NULL,
    contact_phone VARCHAR(40) NOT NULL,
    relationship  VARCHAR(80),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE trip_safety_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users (id) ON DELETE SET NULL,
    event_type  VARCHAR(40) NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    note        TEXT,
    resolved    BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trip_safety_events_booking ON trip_safety_events (booking_id, created_at DESC);
CREATE INDEX idx_trip_safety_events_open_sos ON trip_safety_events (event_type, resolved);
