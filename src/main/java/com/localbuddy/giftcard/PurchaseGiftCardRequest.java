package com.localbuddy.giftcard;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PurchaseGiftCardRequest(
        @NotNull @DecimalMin(value = "1.00") BigDecimal amount,
        @Size(max = 3) String currency,
        @Email @Size(max = 255) String recipientEmail,
        @Size(max = 150) String recipientName,
        @Size(max = 2000) String message
) {
}
