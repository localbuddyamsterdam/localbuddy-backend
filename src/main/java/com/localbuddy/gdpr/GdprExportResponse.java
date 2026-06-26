package com.localbuddy.gdpr;

import com.localbuddy.notification.NotificationPreferenceResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full personal-data export for the authenticated user (GDPR Art. 20 portability). */
public record GdprExportResponse(
        Instant exportedAt,
        ExportAccount account,
        List<ExportConsent> consents,
        NotificationPreferenceResponse notificationPreferences,
        List<ExportBooking> bookings,
        List<ExportPayment> payments,
        List<ExportReview> reviews,
        List<ExportCheckIn> checkIns
) {
    public record ExportAccount(
            UUID id,
            String fullName,
            String email,
            String phone,
            String role,
            String status,
            boolean emailVerified,
            boolean phoneVerified,
            Instant createdAt
    ) {
    }

    public record ExportConsent(
            String consentType,
            String version,
            Instant acceptedAt
    ) {
    }

    public record ExportBooking(
            String bookingReference,
            String status,
            String experienceTitle,
            Instant startTime,
            Integer guestsCount,
            BigDecimal totalAmount,
            String currency,
            Instant createdAt
    ) {
    }

    public record ExportPayment(
            String bookingReference,
            String status,
            BigDecimal amount,
            String currency,
            BigDecimal refundedAmount,
            Instant paidAt,
            Instant createdAt
    ) {
    }

    public record ExportReview(
            String direction,
            Integer rating,
            String comment,
            String status,
            String experienceTitle,
            Instant createdAt
    ) {
    }

    public record ExportCheckIn(
            String bookingReference,
            String experienceTitle,
            Instant checkedInAt,
            Double distanceMeters,
            boolean withinGeofence
    ) {
    }
}
