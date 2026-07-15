package com.localbuddy.booking;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Admin booking cancellation. {@code reason} is required. By default the refund is computed
 * from the active cancellation-refund policy (actor + hours-before-start). An admin may
 * override that with either {@code refundPercentage} (0–100, of the booking total) or an
 * explicit {@code refundAmount}. When both overrides are null the policy applies; when both
 * are set, {@code refundAmount} takes precedence. Bookings with no captured payment (e.g. an
 * admin-created offline booking) simply cancel with nothing to refund.
 *
 * <p>{@code notifyGuest} / {@code notifyHost} control the cancellation emails; null defaults
 * to {@code true} so existing callers keep notifying both parties.
 */
public record AdminCancelBookingRequest(
        @NotBlank(message = "Cancellation reason is required")
        @Size(max = 1000, message = "Cancellation reason cannot exceed 1000 characters") String reason,

        @DecimalMin(value = "0.0", message = "Refund percentage cannot be negative")
        @DecimalMax(value = "100.0", message = "Refund percentage cannot exceed 100")
        BigDecimal refundPercentage,

        @DecimalMin(value = "0.0", message = "Refund amount cannot be negative")
        BigDecimal refundAmount,

        Boolean notifyGuest,

        Boolean notifyHost
) {
    public boolean shouldNotifyGuest() {
        return notifyGuest == null || notifyGuest;
    }

    public boolean shouldNotifyHost() {
        return notifyHost == null || notifyHost;
    }
}
