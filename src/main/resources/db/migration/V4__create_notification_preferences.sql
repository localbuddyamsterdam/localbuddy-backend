-- Per-user notification preferences.
CREATE TABLE notification_preferences (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    booking_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_emails  BOOLEAN NOT NULL DEFAULT FALSE,
    email_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
