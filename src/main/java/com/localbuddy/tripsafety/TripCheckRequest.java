package com.localbuddy.tripsafety;

import jakarta.validation.constraints.Size;

/** Optional location + note for a check-in / check-out. */
public record TripCheckRequest(
        Double latitude,
        Double longitude,
        @Size(max = 2000) String note
) {
}
