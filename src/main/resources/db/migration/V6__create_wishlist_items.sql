-- Traveler wishlists / favorites.
CREATE TABLE wishlist_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    experience_id UUID NOT NULL REFERENCES experiences (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wishlist_user_experience UNIQUE (user_id, experience_id)
);

CREATE INDEX idx_wishlist_items_user ON wishlist_items (user_id, created_at DESC);
