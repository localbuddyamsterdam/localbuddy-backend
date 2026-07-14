-- Forces a password change on next login. Set TRUE when an admin issues a
-- temporary password (onboarding a new user, or resetting one on the user's
-- behalf); cleared when the user sets their own password. Existing users keep
-- the default FALSE.
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
