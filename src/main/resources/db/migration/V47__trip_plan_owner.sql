-- Trip plans gain an optional owner: generation now requires login, so newly created plans
-- are tied to the creating user (for the "My itineraries" account tab). Nullable + ON DELETE
-- SET NULL because existing rows (and the public share-link/guest-checkout flows) have none,
-- and a deleted account shouldn't cascade-delete someone's saved itinerary content.

ALTER TABLE trip_plans ADD COLUMN user_id UUID;
ALTER TABLE trip_plans
    ADD CONSTRAINT fk_trip_plans_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_trip_plans_user ON trip_plans (user_id);
