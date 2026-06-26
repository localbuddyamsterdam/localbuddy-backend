package com.localbuddy.attendance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The host's attendance view for one slot: their own arrival state + the guest roster. */
public record SlotAttendanceResponse(
        UUID slotId,
        String experienceTitle,
        Instant startTime,
        boolean meetingPointSet,
        double geofenceRadiusMeters,
        boolean hostCheckedIn,
        Instant hostCheckedInAt,
        Boolean hostWithinGeofence,
        Double hostDistanceMeters,
        String hostPhotoUrl,
        List<BookingAttendanceRow> bookings
) {
}
