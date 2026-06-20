package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilityStatus;

import java.util.UUID;

public record UnderbookedSlotCancellationResponse(
        UUID slotId,
        AvailabilityStatus slotStatus,
        int cancelledBookings
) {
}
