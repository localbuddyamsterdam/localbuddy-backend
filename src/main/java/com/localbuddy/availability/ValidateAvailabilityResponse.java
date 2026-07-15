package com.localbuddy.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The conflicting subset of proposed sessions (empty list = all clear). */
public record ValidateAvailabilityResponse(
        List<Conflict> conflicts
) {

    public record Conflict(
            Instant start,
            Instant end,
            UUID conflictingSlotId,
            UUID conflictingExperienceId,
            String conflictingExperienceTitle,
            Instant conflictingStart,
            Instant conflictingEnd,
            boolean sameExperience,
            String message
    ) {
    }
}
