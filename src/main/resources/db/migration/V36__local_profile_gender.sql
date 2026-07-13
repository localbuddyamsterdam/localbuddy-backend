-- Add optional gender to local (host) profiles. Used to power the public
-- /locals gender filter. Nullable: existing hosts stay NULL ("not specified")
-- until they set it, and hosts may choose PREFER_NOT_TO_SAY.
ALTER TABLE local_profiles
    ADD COLUMN gender VARCHAR(30);
