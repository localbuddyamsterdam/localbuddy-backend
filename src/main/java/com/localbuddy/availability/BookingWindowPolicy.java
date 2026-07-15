package com.localbuddy.availability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides how late a slot can still be booked. Two cutoffs, because the risk
 * differs once someone has committed:
 *
 * <ul>
 *   <li>a slot with at least one booking closes {@code cutoff-minutes-min}
 *       before it starts — the host is already going to show up, so late
 *       joiners are fine;</li>
 *   <li>an empty slot closes {@code cutoff-minutes-empty} before it starts, so
 *       a cold last-minute booking can't ambush a host who isn't yet committed
 *       to running the session.</li>
 * </ul>
 */
@Component
public class BookingWindowPolicy {

    private final Duration cutoffWithBookings;
    private final Duration cutoffEmpty;

    public BookingWindowPolicy(
            @Value("${app.booking.cutoff-minutes-min:15}") long cutoffMinutesMin,
            @Value("${app.booking.cutoff-minutes-empty:60}") long cutoffMinutesEmpty) {
        this.cutoffWithBookings = Duration.ofMinutes(cutoffMinutesMin);
        this.cutoffEmpty = Duration.ofMinutes(cutoffMinutesEmpty);
    }

    /** The last instant at which a new booking may be made for this slot. */
    public Instant bookingCutoff(AvailabilitySlot slot) {
        Duration lead = slot.getBookedCount() > 0 ? cutoffWithBookings : cutoffEmpty;
        return slot.getStartTime().minus(lead);
    }

    /** Whether the slot is still open for new bookings at {@code now}. */
    public boolean isBookableAt(AvailabilitySlot slot, Instant now) {
        return now.isBefore(bookingCutoff(slot));
    }

    /** Minutes-before-start at which booking closes for this slot (for messaging). */
    public long leadMinutes(AvailabilitySlot slot) {
        return (slot.getBookedCount() > 0 ? cutoffWithBookings : cutoffEmpty).toMinutes();
    }
}
