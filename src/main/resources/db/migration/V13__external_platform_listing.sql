-- Track whether a host lists this experience on external booking platforms (Airbnb, Viator,
-- GetYourGuide, etc.). When true, private-buyout bookings are automatically blocked because
-- LocalBuddy cannot guarantee slot exclusivity when external systems hold bookings.
--
-- price_amount is made nullable to support PRIVATE_ONLY experiences, where the host sets
-- a flat total price (private_price) rather than a per-person rate.

ALTER TABLE experiences
    ADD COLUMN listed_on_external_platform BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN external_listing_details    TEXT,
    ALTER COLUMN price_amount              DROP NOT NULL;
