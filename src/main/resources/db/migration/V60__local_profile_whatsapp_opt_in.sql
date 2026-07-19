-- Host-level WhatsApp opt-in for booking updates (new booking / reminder / cancelled). Distinct
-- from the per-booking traveler opt-in — a host's preference applies across every experience
-- they list, set during onboarding and editable later from the same form (?edit=1).
ALTER TABLE local_profiles ADD COLUMN whatsapp_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
