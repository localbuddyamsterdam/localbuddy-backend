-- Meeting-point coordinates for map view + "near me" distance sorting.
-- Nullable: existing experiences have no coordinate until a host sets one.

ALTER TABLE experiences ADD COLUMN latitude  NUMERIC(9,6);
ALTER TABLE experiences ADD COLUMN longitude NUMERIC(9,6);
