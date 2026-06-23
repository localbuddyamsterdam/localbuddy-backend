package com.localbuddy.booking;

/**
 * Per-booking attendance flag, set when an admin verifies a no-show report.
 * Separate from {@link BookingStatus} so a booking can record who failed to show
 * independently of its lifecycle state. Admins can reset this to {@link #NONE}.
 */
public enum AttendanceOutcome {
    /** No no-show recorded (default). */
    NONE,
    /** The host did not show up — the customer was refunded in full. */
    HOST_NO_SHOW,
    /** The customer did not show up — informational only; the host is still paid. */
    CUSTOMER_NO_SHOW
}
