package com.localbuddy.booking;

import java.math.BigDecimal;

/**
 * What a traveller would be refunded (and what fee they'd forfeit) if they cancel a booking.
 * For an active booking this is priced as of now; for an already-cancelled booking it reproduces
 * the refund that applied at cancellation time. Drives the "no refund / X% cancellation fee"
 * confirmation popup and the cancelled-booking summary.
 */
public record RefundPreviewResponse(
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        BigDecimal refundPercentage,
        BigDecimal cancellationFeePercentage,
        String currency
) {
}
