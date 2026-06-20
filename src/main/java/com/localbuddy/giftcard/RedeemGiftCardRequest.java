package com.localbuddy.giftcard;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RedeemGiftCardRequest(
        @NotBlank String code,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        UUID bookingId
) {
}
