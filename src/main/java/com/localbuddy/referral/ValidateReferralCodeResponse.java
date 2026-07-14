package com.localbuddy.referral;

import java.math.BigDecimal;
import java.util.UUID;

public record ValidateReferralCodeResponse(
        boolean valid,
        UUID referralCodeId,
        UUID ownerUserId,
        String code,
        /** Discount the referred user gets for using this code (0 when invalid). */
        BigDecimal discountAmount,
        String message
) {
}
