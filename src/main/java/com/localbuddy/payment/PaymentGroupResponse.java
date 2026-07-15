package com.localbuddy.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Public view of a bundle checkout, addressed only by its unguessable group token
 * (returned at creation and carried on the Stripe success/cancel URLs).
 */
public record PaymentGroupResponse(
        String groupToken,
        PaymentGroupStatus status,
        BigDecimal totalAmount,
        BigDecimal giftCardAmount,
        String currency,
        String checkoutUrl,
        Instant createdAt,
        Instant paidAt,
        List<PaymentGroupMemberResponse> bookings
) {
}
