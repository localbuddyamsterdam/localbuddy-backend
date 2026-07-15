package com.localbuddy.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        String bookingReference,
        UUID loggedInUserId,
        String guestFirstName,
        String guestLastName,
        String guestEmail,
        String guestPhone,
        boolean guestEmailVerified,
        boolean guestPhoneVerified,
        BookingSource bookingSource,
        UUID localProfileId,
        UUID experienceId,
        UUID availabilitySlotId,
        Integer guestsCount,
        BookingStatus status,
        BigDecimal pricePerGuest,
        BigDecimal totalAmount,
        String currency,
        String travelerNote,
        String localResponseNote,
        String cancellationReason,
        Instant requestedAt,
        Instant acceptedAt,
        Instant declinedAt,
        Instant cancelledAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        Boolean guestTermsAccepted,
        Boolean guestSafetyAccepted,
        Boolean guestLiabilityAccepted,
        String guestConsentVersion,
        Instant guestConsentAcceptedAt,
        UUID promoCodeId,
        UUID referralCodeId,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        String promoCodeText,
        String referralCodeText,
        boolean privateBooking,
        BigDecimal privateDiscountAmount,
        Integer seatsBlocked,
        UUID dealId,
        BigDecimal dealDiscountAmount,
        // Denormalized slot/experience context so clients can render a booking
        // without extra lookups (a bare id-only response displays as empty).
        Instant slotStartTime,
        Instant slotEndTime,
        String experienceTitle,
        // Denormalized host/city + attendance + age bands for the admin console, so it can
        // list, filter and manage bookings without per-row experience/host lookups.
        String hostName,
        String cityName,
        AttendanceOutcome attendanceOutcome,
        Instant noShowMarkedAt,
        GuestShowStatus guestShowStatus,
        Integer adultsCount,
        Integer teensCount,
        Integer childrenCount,
        Integer infantsCount,
        // Emergency-contact snapshot captured at checkout (null when not provided).
        String emergencyContactFirstName,
        String emergencyContactLastName,
        String emergencyContactEmail,
        String emergencyContactPhone,
        String emergencyContactRelationship
) {
}