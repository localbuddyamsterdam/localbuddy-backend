package com.localbuddy.noshow;

import jakarta.validation.constraints.Size;

/** Body for a host or customer filing a no-show complaint on one of their bookings. */
public record CreateNoShowReportRequest(
        @Size(max = 2000, message = "Reason cannot exceed 2000 characters")
        String reason
) {
}
