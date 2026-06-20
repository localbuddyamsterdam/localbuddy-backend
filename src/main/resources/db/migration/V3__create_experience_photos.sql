-- Experience photos / media gallery.
CREATE TABLE experience_photos (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    experience_id UUID NOT NULL REFERENCES experiences (id) ON DELETE CASCADE,
    storage_key   VARCHAR(512),
    url           TEXT NOT NULL,
    caption       VARCHAR(300),
    content_type  VARCHAR(100),
    size_bytes    BIGINT,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    is_cover      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_experience_photos_experience ON experience_photos (experience_id, sort_order);
