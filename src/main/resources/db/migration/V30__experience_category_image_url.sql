-- Category tile image for the frontend catalog. Seed the existing categories
-- from the bundled illustration set (served at /illustrations/<slug>.png, the
-- same images used as email hero fallbacks). Relative URLs so every
-- environment resolves them against its own host (dev proxy / prod reverse proxy).
ALTER TABLE experience_categories ADD COLUMN image_url VARCHAR(500);

UPDATE experience_categories
SET image_url = '/illustrations/' || slug || '.png'
WHERE slug IN ('food', 'photo-walk', 'hidden-gems', 'local-markets',
               'cafe-hopping', 'student-life', 'nightlife', 'custom');
