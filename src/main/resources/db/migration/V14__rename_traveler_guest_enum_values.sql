UPDATE users SET role = 'LOGGED_IN_USER' WHERE role = 'TRAVELER';
UPDATE bookings SET booking_source = 'GUEST_USER' WHERE booking_source = 'GUEST';
UPDATE bookings SET status = 'CANCELLED_BY_LOGGED_IN_USER' WHERE status = 'CANCELLED_BY_TRAVELER';
