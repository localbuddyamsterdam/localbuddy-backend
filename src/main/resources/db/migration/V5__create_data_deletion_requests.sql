-- GDPR data deletion / anonymization requests.
CREATE TABLE data_deletion_requests (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason       TEXT,
    status       VARCHAR(40) NOT NULL DEFAULT 'REQUESTED',
    admin_note   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_data_deletion_requests_status ON data_deletion_requests (status);
CREATE INDEX idx_data_deletion_requests_user ON data_deletion_requests (user_id);
