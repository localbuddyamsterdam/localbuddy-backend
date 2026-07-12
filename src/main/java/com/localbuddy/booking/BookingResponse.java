package com.localbuddy.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        String bookingReference,
        UUID loggedInUserId,
        String guestName,
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
        String experienceTitle
) {
}