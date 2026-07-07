-- Blob storage key for an uploaded host profile photo. Nullable: profiles that
-- supplied an external profile_photo_url (rather than uploading) have no key.
ALTER TABLE local_profiles
    ADD COLUMN IF NOT EXISTS profile_photo_storage_key VARCHAR(512);
