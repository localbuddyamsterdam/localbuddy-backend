-- Trip Genie budget: optional whole-group total (EUR) the traveler wants the
-- itinerary's bookable experiences to fit within. Nullable — no budget means
-- "no limit". Whole euros; cent precision adds nothing to a planning target.
ALTER TABLE trip_plans ADD COLUMN budget INTEGER;
ALTER TABLE trip_plans ADD CONSTRAINT chk_trip_plans_budget CHECK (budget IS NULL OR budget > 0);
