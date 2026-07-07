-- Allow a host to delete their profile photo (full CRUD). profile_photo_url was
-- NOT NULL (required at creation); relax it so it can be cleared on delete.
ALTER TABLE local_profiles
    ALTER COLUMN profile_photo_url DROP NOT NULL;
