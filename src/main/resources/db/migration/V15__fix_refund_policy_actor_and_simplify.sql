-- Fixes two things on already-migrated databases:
--   1. The TRAVELER -> LOGGED_IN_USER actor rename left cancellation_refund_policies
--      rows storing the obsolete 'TRAVELER' value, which BookingCancellationActor
--      (EnumType.STRING) can no longer deserialize. V14 missed this table.
--   2. Simplifies the traveler refund policy to a single rule:
--      24h+ before start -> 100% refund, otherwise 0%.
--
-- Host (LOCAL) and admin (ADMIN) cancellations always fully refund the customer.

DELETE FROM cancellation_refund_policies WHERE cancelled_by = 'TRAVELER';

INSERT INTO cancellation_refund_policies (
    id, name, cancelled_by, min_hours_before_start, max_hours_before_start,
    refund_percentage, active, created_at, updated_at
)
VALUES
    ('11111111-1111-1111-1111-111111111101', 'Traveler cancellation - 24+ hours before start',          'LOGGED_IN_USER', 24.00, NULL,  100.00, TRUE, NOW(), NOW()),
    ('11111111-1111-1111-1111-111111111102', 'Traveler cancellation - less than 24 hours before start', 'LOGGED_IN_USER',  0.00, 24.00,    0.00, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
