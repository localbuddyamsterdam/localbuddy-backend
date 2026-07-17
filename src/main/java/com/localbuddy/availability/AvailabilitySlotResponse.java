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
        Instant updatedAt,
        // IANA timezone of the experience's city (e.g. "Europe/Amsterdam"). The start/end instants
        // are UTC; clients must render them in this zone, not the browser's, or times shift by the
        // city's UTC offset.
        String timezone
) {
}