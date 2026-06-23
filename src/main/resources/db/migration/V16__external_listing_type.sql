-- Replaces the boolean listed_on_external_platform (V13) with a three-way enum:
--   NONE                -> not listed elsewhere
--   OWN_WEBSITE_SOCIAL  -> host's own website / social media (private bookings still allowed)
--   AGGREGATOR_PLATFORM -> third-party aggregator (Airbnb, Viator, …) — blocks private bookings
--
-- Old data carries no signal for OWN_WEBSITE_SOCIAL, so true -> AGGREGATOR_PLATFORM (the blocking
-- value, the safe default) and false -> NONE.

ALTER TABLE experiences ADD COLUMN external_listing_type VARCHAR(40);

UPDATE experiences
   SET external_listing_type = CASE
        WHEN listed_on_external_platform THEN 'AGGREGATOR_PLATFORM'
        ELSE 'NONE'
   END;

ALTER TABLE experiences ALTER COLUMN external_listing_type SET DEFAULT 'NONE';
ALTER TABLE experiences ALTER COLUMN external_listing_type SET NOT NULL;

ALTER TABLE experiences DROP COLUMN listed_on_external_platform;
