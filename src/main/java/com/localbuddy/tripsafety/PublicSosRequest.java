package com.localbuddy.tripsafety;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anonymous-guest SOS. Identity comes from the booking reference + guest email (there is
 * no logged-in principal on the public endpoint). Location and enrichment are best-effort,
 * exactly like the authenticated {@link SosRequest} — a guest SOS is never rejected for
 * missing coordinates.
 */
public record PublicSosRequest(
        @NotBlank @Size(max = 40) String bookingReference,
        @NotBlank @Email @Size(max = 255) String guestEmail,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 2000) String message,
        SosSituationType situationType,
        SosContactPreference contactPreference,
        @DecimalMin("0.0") @DecimalMax("100000.0") Double accuracyMeters,
        @Size(max = 20) String locationSource,
        @Min(0) @Max(100) Integer batteryPercent,
        @Size(max = 20) String deviceLanguage
) {
    public SosRequest toSosRequest() {
        return new SosRequest(latitude, longitude, message, situationType, contactPreference,
                accuracyMeters, locationSource, batteryPercent, deviceLanguage);
    }
}
