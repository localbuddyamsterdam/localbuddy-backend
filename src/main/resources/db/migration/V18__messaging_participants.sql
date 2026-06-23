-- Generalize messaging to participant-based, typed conversations + per-message sender role.
-- Membership moves from the two hardcoded columns (traveler_user_id / host_user_id) into
-- conversation_participants, so a thread can be customer↔host, an admin side conversation,
-- or a customer↔host thread an admin has joined.

ALTER TABLE conversations ADD COLUMN type    VARCHAR(30) NOT NULL DEFAULT 'CUSTOMER_HOST';
ALTER TABLE conversations ADD COLUMN subject TEXT;

CREATE TABLE conversation_participants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    role            VARCHAR(20) NOT NULL,
    last_read_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_cp_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_cp_user         FOREIGN KEY (user_id)         REFERENCES users (id),
    CONSTRAINT uq_cp_conversation_user UNIQUE (conversation_id, user_id)
);

CREATE INDEX idx_cp_user         ON conversation_participants (user_id);
CREATE INDEX idx_cp_conversation ON conversation_participants (conversation_id);

-- Backfill participants from the existing two-party columns.
INSERT INTO conversation_participants (conversation_id, user_id, role, created_at)
SELECT id, traveler_user_id, 'CUSTOMER', created_at FROM conversations WHERE traveler_user_id IS NOT NULL;
INSERT INTO conversation_participants (conversation_id, user_id, role, created_at)
SELECT id, host_user_id,     'HOST',     created_at FROM conversations WHERE host_user_id IS NOT NULL;

-- Per-message sender role; backfill from the conversation's two parties before dropping them.
ALTER TABLE messages ADD COLUMN sender_role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';
UPDATE messages m SET sender_role = 'HOST'
  FROM conversations c WHERE m.conversation_id = c.id AND m.sender_user_id = c.host_user_id;
UPDATE messages m SET sender_role = 'CUSTOMER'
  FROM conversations c WHERE m.conversation_id = c.id AND m.sender_user_id = c.traveler_user_id;

-- Drop the superseded two-party columns (participants are now authoritative).
DROP INDEX IF EXISTS idx_conversations_traveler_user_id;
DROP INDEX IF EXISTS idx_conversations_host_user_id;
ALTER TABLE conversations DROP CONSTRAINT IF EXISTS fk_conversations_traveler_user;
ALTER TABLE conversations DROP CONSTRAINT IF EXISTS fk_conversations_host_user;
ALTER TABLE conversations DROP COLUMN traveler_user_id;
ALTER TABLE conversations DROP COLUMN host_user_id;
