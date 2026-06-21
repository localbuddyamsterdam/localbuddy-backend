-- Age-band breakdown captured on each booking (seats = sum; pricing weights bands).
ALTER TABLE bookings ADD COLUMN adults_count   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN teens_count    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN children_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN infants_count  INTEGER NOT NULL DEFAULT 0;
