package com.localbuddy.whatsapp;

/** A click-to-chat link to message the other party on a booking. */
public record BookingContactLinkResponse(
        String counterpartRole,
        String link
) {
}
