package com.localbuddy.payout;

import java.math.BigDecimal;

public record HostEarningsResponse(
        BigDecimal totalEarned,
        BigDecimal totalPaidOut,
        BigDecimal availableBalance,
        BigDecimal onHoldBalance,
        String currency
) {
}
