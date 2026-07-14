-- Host-submitted category suggestions. From the listing form a host can propose
-- a new experience category; the proposal lands in a review queue. An admin then
-- decides whether to add it in Catalog → Categories — a suggestion NEVER creates
-- a category on its own (that stays a deliberate admin action). resulting_category_id
-- links a suggestion to the category an admin eventually created from it.
CREATE TABLE experience_category_suggestions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suggested_name        VARCHAR(100) NOT NULL,
    note                  TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    suggested_by_user_id  UUID,
    resulting_category_id UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_at           TIMESTAMPTZ,

    CONSTRAINT fk_ecs_user
        FOREIGN KEY (suggested_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_ecs_category
        FOREIGN KEY (resulting_category_id) REFERENCES experience_categories (id) ON DELETE SET NULL,
    CONSTRAINT chk_ecs_status
        CHECK (status IN ('PENDING', 'APPROVED', 'DISMISSED'))
);

CREATE INDEX idx_ecs_status     ON experience_category_suggestions (status);
CREATE INDEX idx_ecs_created_at ON experience_category_suggestions (created_at DESC);
