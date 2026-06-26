package com.localbuddy.attendance;

import com.localbuddy.booking.GuestShowStatus;
import jakarta.validation.constraints.NotNull;

/** Host marks a booking's guest(s) as shown or not. One mark per booking. */
public record MarkAttendanceRequest(
        @NotNull(message = "Status is required")
        GuestShowStatus status
) {
}
