-- Rendered HTML body for email notifications. Nullable: the plain-text `message`
-- column remains the fallback and the body for non-email channels.
ALTER TABLE notifications ADD COLUMN html_body TEXT;
