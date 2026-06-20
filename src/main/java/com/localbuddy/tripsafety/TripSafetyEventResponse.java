package com.localbuddy.tripsafety;

import java.time.Instant;
import java.util.UUID;

public record TripSafetyEventResponse(
        UUID id,
        UUID bookingId,
        TripSafetyEventType eventType,
        Double latitude,
        Double longitude,
        String note,
        boolean resolved,
        Instant resolvedAt,
        Instant createdAt
) {
    public static TripSafetyEventResponse from(TripSafetyEvent event) {
        return new TripSafetyEventResponse(
                event.getId(),
                event.getBooking().getId(),
                event.getEventType(),
                event.getLatitude(),
                event.getLongitude(),
                event.getNote(),
                event.isResolved(),
                event.getResolvedAt(),
                event.getCreatedAt()
        );
    }
}
