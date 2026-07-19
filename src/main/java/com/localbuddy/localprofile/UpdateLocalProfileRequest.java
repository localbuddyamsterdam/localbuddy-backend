package com.localbuddy.localprofile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateLocalProfileRequest(

        @NotBlank(message = "Display name is required")
        @Size(max = 150, message = "Display name cannot exceed 150 characters")
        String displayName,

        @NotBlank(message = "Phone number is required")
        @Size(max = 40, message = "Phone number cannot exceed 40 characters")
        String phoneNumber,

        /** Opt in to WhatsApp updates (new booking / reminder / cancelled); null/false = opted out. */
        Boolean whatsappOptIn,

        @NotBlank(message = "Bio is required")
        @Size(max = 2000, message = "Bio cannot exceed 2000 characters")
        String bio,

        @Size(max = 2000, message = "Profile photo URL cannot exceed 2000 characters")
        String profilePhotoUrl,

        @NotBlank(message = "Host city is required")
        @Size(max = 100, message = "Host city cannot exceed 100 characters")
        String hostCity,

        @NotBlank(message = "Zip code is required")
        @Size(max = 20, message = "Zip code cannot exceed 20 characters")
        String zipCode,

        @NotBlank(message = "Country is required")
        @Size(max = 100, message = "Country cannot exceed 100 characters")
        String country,

        @NotEmpty(message = "At least one experience city is required")
        List<UUID> experienceCityIds,

        @NotEmpty(message = "At least one experience category is required")
        List<UUID> experienceCategoryIds,

        @NotEmpty(message = "At least one experience language is required")
        List<String> experienceLanguages,

        @NotBlank(message = "Motivation is required")
        @Size(max = 3000, message = "Motivation cannot exceed 3000 characters")
        String motivation,

        @NotBlank(message = "Experience info is required")
        @Size(max = 5000, message = "Experience info cannot exceed 5000 characters")
        String experienceInfo,

        @NotBlank(message = "Legal first name is required")
        @Size(max = 120, message = "Legal first name cannot exceed 120 characters")
        String legalFirstName,

        @NotBlank(message = "Legal last name is required")
        @Size(min = 2, max = 120, message = "Legal last name must be between 2 and 120 characters")
        String legalLastName,

        @NotBlank(message = "Preferred name is required")
        @Size(max = 120, message = "Preferred name cannot exceed 120 characters")
        String preferredName,

        @NotBlank(message = "Current address is required")
        @Size(max = 2000, message = "Current address cannot exceed 2000 characters")
        String currentAddress,

        /** Optional host-declared gender. */
        Gender gender,

        @Size(max = 64, message = "Account number cannot exceed 64 characters")
        String accountNumber,

        @Size(max = 150, message = "Account name cannot exceed 150 characters")
        String accountName,

        @Size(max = 32, message = "Swift code cannot exceed 32 characters")
        String swiftCode
) {
}
