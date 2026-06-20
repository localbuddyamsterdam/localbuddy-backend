package com.localbuddy.tripsafety;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertEmergencyContactRequest(
        @NotBlank @Size(max = 150) String contactName,
        @NotBlank @Size(max = 40) String contactPhone,
        @Size(max = 80) String relationship
) {
}
