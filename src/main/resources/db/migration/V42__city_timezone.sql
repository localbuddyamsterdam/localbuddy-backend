-- Availability is generated and displayed in each city's own local wall-clock
-- time. Until now the zone was hard-coded to Europe/Amsterdam in
-- AvailabilitySlotService; making it a property of the city lets the platform
-- schedule correctly as it expands beyond Amsterdam (Paris is already seeded).
ALTER TABLE cities
    ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Amsterdam';

-- Backfill the non-Amsterdam cities we already seed. Every other existing row
-- keeps the Europe/Amsterdam default, which is correct for the launch market.
UPDATE cities SET timezone = 'Europe/Paris' WHERE slug = 'paris';
