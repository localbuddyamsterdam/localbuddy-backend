-- Traveller profile self-service: avatar (blob-hosted) + spoken languages (CSV).
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500);
ALTER TABLE users ADD COLUMN avatar_storage_key VARCHAR(300);
ALTER TABLE users ADD COLUMN languages VARCHAR(300);
