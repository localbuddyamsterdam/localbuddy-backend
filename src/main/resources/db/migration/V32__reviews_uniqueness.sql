-- Enforce at the database what ReviewService only checked in application code
-- (existsByBookingIdAndDirection): at most one review per booking per direction.
-- A booking can still have TWO reviews — one TRAVELER_TO_HOST and one HOST_TO_TRAVELER —
-- but not two in the same direction (which a race between concurrent requests could otherwise
-- create). See the note in V1__baseline_schema.sql on why booking_id itself is not unique.
CREATE UNIQUE INDEX ux_reviews_booking_direction
    ON reviews (booking_id, direction);
