package com.localbuddy.noshow;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for a guest customer (no account) filing a host no-show refund claim. The guest is verified
 * by their booking reference + guest email, mirroring the guest payment-lookup flow.
 */
public record GuestNoShowReportRequest(

        @NotBlank(message = "Booking reference is required")
        @Size(max = 40, message = "Booking reference cannot exceed 40 characters")
        String bookingReference,

        @NotBlank(message = "Guest email is required")
        @Email(message = "Guest email must be valid")
        @Size(max = 255, message = "Guest email cannot exceed 255 characters")
        String guestEmail,

        @Size(max = 2000, message = "Reason cannot exceed 2000 characters")
        String reason
) {
}
