-- Admin "book without payment": bookings confirmed via the customer flow with the
-- admin-only skip-payment option. No payments row exists for them; finance/reporting
-- must exclude them, hence the explicit flag rather than inferring from a missing payment.
ALTER TABLE bookings
    ADD COLUMN payment_waived BOOLEAN NOT NULL DEFAULT FALSE;
