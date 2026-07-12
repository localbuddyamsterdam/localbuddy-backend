package com.localbuddy.availability;

import java.util.List;

/** Result of a bulk generation: what was created and what was skipped (and why). */
public record GenerateAvailabilityResponse(
        int created,
        int skippedExisting,
        int skippedPast,
        List<AvailabilitySlotResponse> slots
) {
}
