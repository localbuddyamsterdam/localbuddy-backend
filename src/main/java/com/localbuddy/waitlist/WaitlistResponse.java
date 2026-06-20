package com.localbuddy.waitlist;

import java.time.Instant;
import java.util.UUID;

public record WaitlistResponse(
        UUID id,
        UUID availabilitySlotId,
        UUID experienceId,
        UUID userId,
        String guestEmail,
        Integer guestsCount,
        WaitlistStatus status,
        Instant createdAt,
        Instant notifiedAt
) {
}
