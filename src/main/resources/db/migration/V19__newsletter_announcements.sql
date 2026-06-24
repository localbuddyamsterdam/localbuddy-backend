-- Newsletter subscriptions, host/platform announcements, and host follows.
-- (Wishlist + abandoned-booking reminders need no schema — idempotency rides on notifications.dedupe_key.)

CREATE TABLE newsletter_subscriptions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email             VARCHAR(255) NOT NULL,
    user_id           UUID,
    audience          VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    confirm_token     VARCHAR(80),
    unsubscribe_token VARCHAR(80)  NOT NULL,
    source            VARCHAR(80),
    confirmed_at      TIMESTAMPTZ,
    unsubscribed_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_newsletter_email UNIQUE (email),
    CONSTRAINT fk_newsletter_user  FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_newsletter_status            ON newsletter_subscriptions (status);
CREATE INDEX idx_newsletter_confirm_token     ON newsletter_subscriptions (confirm_token);
CREATE INDEX idx_newsletter_unsubscribe_token ON newsletter_subscriptions (unsubscribe_token);

CREATE TABLE announcements (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    local_profile_id   UUID,
    created_by_user_id UUID         NOT NULL,
    audience           VARCHAR(20)  NOT NULL,
    subject            VARCHAR(200) NOT NULL,
    body               TEXT         NOT NULL,
    recipient_count    INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_announcement_local_profile FOREIGN KEY (local_profile_id)   REFERENCES local_profiles (id),
    CONSTRAINT fk_announcement_created_by    FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);
CREATE INDEX idx_announcements_local_profile ON announcements (local_profile_id);

CREATE TABLE host_follows (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_user_id UUID        NOT NULL,
    local_profile_id UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_host_follow         UNIQUE (follower_user_id, local_profile_id),
    CONSTRAINT fk_host_follow_user    FOREIGN KEY (follower_user_id) REFERENCES users (id),
    CONSTRAINT fk_host_follow_profile FOREIGN KEY (local_profile_id) REFERENCES local_profiles (id)
);
CREATE INDEX idx_host_follows_profile  ON host_follows (local_profile_id);
CREATE INDEX idx_host_follows_follower ON host_follows (follower_user_id);
