package com.localbuddy.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Admin partial edit of a booking's guest contact details + notes. Null fields are left
 * unchanged. Contact fields (name/email/phone) are only applied when a non-blank value is
 * given — they can be corrected but never cleared, so DB guest-contact constraints hold.
 * A blank string on either note column clears it. Does not touch pricing, party or slot —
 * those have dedicated endpoints.
 *
 * <p>Emergency-contact fields are grouped: when any of them is non-null the whole group is
 * re-applied (first name + phone are the minimum); sending all five as blank strings clears
 * the stored contact. All-null leaves the contact untouched.
 */
public record AdminUpdateBookingRequest(
        @Size(max = 100, message = "First name cannot exceed 100 characters") String guestFirstName,

        @Size(min = 2, max = 100, message = "Last name must be between 2 and 100 characters") String guestLastName,

        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters") String guestEmail,

        @Size(max = 40, message = "Guest phone cannot exceed 40 characters") String guestPhone,

        @Size(max = 2000, message = "Traveller note cannot exceed 2000 characters") String travelerNote,

        @Size(max = 2000, message = "Host response note cannot exceed 2000 characters") String localResponseNote,

        @Size(max = 100, message = "Emergency contact first name cannot exceed 100 characters")
        String emergencyContactFirstName,

        @Size(max = 100, message = "Emergency contact last name cannot exceed 100 characters")
        String emergencyContactLastName,

        @Email(message = "Emergency contact email must be valid")
        @Size(max = 255, message = "Emergency contact email cannot exceed 255 characters")
        String emergencyContactEmail,

        @Size(max = 40, message = "Emergency contact phone cannot exceed 40 characters")
        String emergencyContactPhone,

        @Size(max = 80, message = "Emergency contact relationship cannot exceed 80 characters")
        String emergencyContactRelationship
) {
    /** True when the request touches the emergency-contact group at all. */
    public boolean touchesEmergencyContact() {
        return emergencyContactFirstName != null || emergencyContactLastName != null
                || emergencyContactEmail != null || emergencyContactPhone != null
                || emergencyContactRelationship != null;
    }
}
