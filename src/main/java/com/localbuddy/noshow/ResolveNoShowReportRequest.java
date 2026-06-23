package com.localbuddy.noshow;

import jakarta.validation.constraints.Size;

/** Admin note attached when approving or rejecting a no-show report. */
public record ResolveNoShowReportRequest(
        @Size(max = 2000, message = "Admin note cannot exceed 2000 characters")
        String adminNote
) {
}
