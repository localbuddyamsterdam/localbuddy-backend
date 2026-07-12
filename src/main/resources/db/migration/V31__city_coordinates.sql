-- City centre coordinates — used to centre the map and to fetch weather for a
-- city when an experience has no precise meeting-point coordinates of its own.
ALTER TABLE cities ADD COLUMN latitude NUMERIC(9, 6);
ALTER TABLE cities ADD COLUMN longitude NUMERIC(9, 6);

UPDATE cities SET latitude = 52.367600, longitude = 4.904100 WHERE slug = 'amsterdam';
