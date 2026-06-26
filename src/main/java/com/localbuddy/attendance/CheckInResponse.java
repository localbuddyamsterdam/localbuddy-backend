package com.localbuddy.attendance;

import java.time.Instant;

/**
 * The result of a check-in. For guests this is only returned on success (they are hard-gated).
 * For hosts it is always returned; {@code warning} is set when the host is outside the geofence.
 */
public record CheckInResponse(
        CheckInRole role,
        Instant checkedInAt,
        boolean withinGeofence,
        Double distanceMeters,
        double geofenceRadiusMeters,
        String warning,
        String photoUrl
) {
}
