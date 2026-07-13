package com.localbuddy.booking;

import java.time.Instant;
import java.util.UUID;

/** A booking audit entry with the acting admin's name resolved for display. */
public record BookingAuditResponse(
        UUID id,
        String action,
        String detail,
        UUID changedByUserId,
        String changedByName,
        Instant changedAt
) {
}
