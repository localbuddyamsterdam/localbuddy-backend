-- Trip Genie upgrades:
--  * language — the itinerary text's language (en/nl/fr), chosen from the traveler's UI
--    language at generation time; also drives the PDF's static labels.
--  * feedback_helpful / feedback_at — one-tap "was this itinerary helpful?" from the plan
--    page. Joined against the stored `model` column this turns the Haiku-vs-Sonnet model
--    choice into measurable data instead of taste.

ALTER TABLE trip_plans ADD COLUMN language VARCHAR(5) NOT NULL DEFAULT 'en';
ALTER TABLE trip_plans ADD COLUMN feedback_helpful BOOLEAN;
ALTER TABLE trip_plans ADD COLUMN feedback_at TIMESTAMPTZ;
