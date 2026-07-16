-- Trip planner shared/private toggle: a plan generated for private whole-slot buyouts is
-- flagged so viewing (availability refresh) and bundle checkout book it as a private tour.
ALTER TABLE trip_plans
    ADD COLUMN private_tour BOOLEAN NOT NULL DEFAULT FALSE;
