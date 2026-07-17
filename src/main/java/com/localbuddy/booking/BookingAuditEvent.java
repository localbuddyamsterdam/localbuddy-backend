package com.localbuddy.booking;

import java.util.UUID;

/**
 * Published by {@link BookingService} when a booking is created or changed by a traveller, host or
 * the system, so the change lands in the {@code booking_change_audit} timeline exactly like admin
 * actions do. Recorded after the booking transaction commits (see {@code BookingAuditListener}), so
 * a failed audit can never roll back the booking, and a rolled-back booking is never audited.
 *
 * @param actorUserId the acting user, or null for guest/system actions
 * @param actorRole   TRAVELER | HOST | ADMIN | GUEST | SYSTEM
 */
public record BookingAuditEvent(
        UUID bookingId,
        String action,
        String detail,
        UUID actorUserId,
        String actorRole
) {
}
