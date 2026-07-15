package com.localbuddy.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One booking inside a bundle checkout, as shown on the group status / success page. */
public record PaymentGroupMemberResponse(
        UUID bookingId,
        String bookingReference,
        String experienceTitle,
        Instant slotStartTime,
        String bookingStatus,
        PaymentStatus paymentStatus,
        BigDecimal amount
) {
}
