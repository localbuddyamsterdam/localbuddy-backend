package com.localbuddy.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Admin partial edit of a booking's guest contact details + notes. Null fields are left
 * unchanged. Contact fields (name/email/phone) are only applied when a non-blank value is
 * given — they can be corrected but never cleared, so DB guest-contact constraints hold.
 * A blank string on either note column clears it. Does not touch pricing, party or slot —
 * those have dedicated endpoints.
 */
public record AdminUpdateBookingRequest(
        @Size(max = 150, message = "Guest name cannot exceed 150 characters") String guestName,

        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters") String guestEmail,

        @Size(max = 40, message = "Guest phone cannot exceed 40 characters") String guestPhone,

        @Size(max = 2000, message = "Traveller note cannot exceed 2000 characters") String travelerNote,

        @Size(max = 2000, message = "Host response note cannot exceed 2000 characters") String localResponseNote
) {
}
