-- Enrich SOS events so an alert is actionable — what kind of help is needed, whether
-- it is safe to call the traveler, how good the location fix is, and phone battery —
-- plus a reserved slot for a reverse-geocoded street address (populated later).
--
-- All columns are nullable: check-in / check-out events and pre-existing rows simply
-- leave them empty. Owned by Flyway (schema is never hand-edited).
ALTER TABLE trip_safety_events
    ADD COLUMN situation_type     VARCHAR(40),
    ADD COLUMN contact_preference VARCHAR(40),
    ADD COLUMN accuracy_meters    DOUBLE PRECISION,
    ADD COLUMN location_source    VARCHAR(20),
    ADD COLUMN battery_percent    INTEGER,
    ADD COLUMN device_language    VARCHAR(20),
    ADD COLUMN geocoded_address   VARCHAR(500);
