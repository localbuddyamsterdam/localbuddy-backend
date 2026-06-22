package com.localbuddy.wallet;

import com.localbuddy.booking.Booking;
import com.localbuddy.experience.Experience;

import java.time.Instant;

/** The booking details rendered onto a wallet pass (Apple or Google). */
public record WalletPassData(
        String bookingReference,
        String experienceTitle,
        String hostName,
        Instant startTime,
        Instant endTime,
        String location,
        int guests
) {
    /** Maps a booking onto the fields shown on a wallet pass. */
    public static WalletPassData from(Booking booking) {
        Experience experience = booking.getExperience();
        String title = experience != null && experience.getTitle() != null
                ? experience.getTitle() : "LocalBuddy experience";

        String hostName = null;
        if (booking.getLocalProfile() != null && booking.getLocalProfile().getUser() != null) {
            hostName = booking.getLocalProfile().getUser().getFullName();
        }

        Instant start = booking.getAvailabilitySlot() != null
                ? booking.getAvailabilitySlot().getStartTime() : null;
        Instant end = booking.getAvailabilitySlot() != null
                ? booking.getAvailabilitySlot().getEndTime() : null;

        String location = null;
        if (experience != null) {
            if (experience.getMeetingArea() != null && !experience.getMeetingArea().isBlank()) {
                location = experience.getMeetingArea();
            } else if (experience.getCity() != null) {
                location = experience.getCity().getName();
            }
        }

        int guests = booking.getGuestsCount() != null ? booking.getGuestsCount() : 1;

        return new WalletPassData(booking.getBookingReference(), title, hostName, start, end, location, guests);
    }
}
