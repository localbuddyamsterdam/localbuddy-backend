package com.localbuddy.availability;

import java.util.UUID;

/** Result of a host cancelling an entire session (slot): how many bookings were refunded and cancelled. */
public record SessionCancellationResponse(
        UUID slotId,
        int cancelledBookings
) {
}
