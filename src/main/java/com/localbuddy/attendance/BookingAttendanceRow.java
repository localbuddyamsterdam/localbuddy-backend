package com.localbuddy.attendance;

import com.localbuddy.booking.GuestShowStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One CONFIRMED booking in the host's attendance list. The host uses this to know who has arrived
 * (checked in nearby) and to mark show / no-show. Distance is null when the guest hasn't checked in.
 */
public record BookingAttendanceRow(
        UUID bookingId,
        String bookingReference,
        String guestDisplayName,
        String guestPhone,
        int partySize,
        GuestShowStatus guestShowStatus,
        boolean guestCheckedIn,
        Instant guestCheckedInAt,
        Double guestDistanceMeters,
        boolean guestWithinGeofence
) {
}
