-- Trip Genie lifecycle + funnel:
--  * viewed_count / last_viewed_at — how often the plan page was opened (share-token GET).
--    A plan is always viewed once right after generation; viewed_count >= 2 means the
--    traveler came back to it (or shared it) — the "re-engaged" step of the funnel.
--  * idx_trip_plans_status_end_date — the auto-archive sweep scans ACTIVE plans whose
--    end_date has passed.

ALTER TABLE trip_plans ADD COLUMN viewed_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trip_plans ADD COLUMN last_viewed_at TIMESTAMPTZ;

CREATE INDEX idx_trip_plans_status_end_date ON trip_plans (status, end_date);
