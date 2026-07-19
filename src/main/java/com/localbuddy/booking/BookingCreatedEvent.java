package com.localbuddy.booking;

import java.util.UUID;

/**
 * @param notifyTraveler whether the traveler/guest "complete payment" copy should be sent for this
 *                        booking. False for bundle-checkout bookings (trip-plan multi-item), where
 *                        the caller sends one combined email after all items are booked instead of
 *                        one per booking — the host copy is unaffected and always sent.
 */
public record BookingCreatedEvent(UUID bookingId, boolean notifyTraveler) {

    public BookingCreatedEvent(UUID bookingId) {
        this(bookingId, true);
    }
}