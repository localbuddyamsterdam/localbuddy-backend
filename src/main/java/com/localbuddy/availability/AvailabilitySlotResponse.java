package com.localbuddy.availability;

import java.time.Instant;
import java.util.UUID;

public record AvailabilitySlotResponse(
        UUID id,
        UUID experienceId,
        UUID localProfileId,
        Instant startTime,
        Instant endTime,
        Integer capacity,
        Integer bookedCount,
        Integer remainingCapacity,
        boolean privateBookingAvailable,
        AvailabilityStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}