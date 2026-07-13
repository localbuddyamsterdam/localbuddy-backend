package com.localbuddy.tripsafety;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertEmergencyContactRequest(
        @NotBlank @Size(max = 150) String contactName,
        // E.164-ish: optional leading +, then 7–15 digits. Rejects free text on a safety-critical field.
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be a valid phone number")
        String contactPhone,
        @Size(max = 80) String relationship
) {
}
