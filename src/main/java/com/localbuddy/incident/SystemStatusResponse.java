package com.localbuddy.incident;

import java.time.Instant;

/**
 * Public, unauthenticated banner payload served from {@code GET /api/public/system-status}.
 * Minimal by design — the frontend only needs to know whether to show the banner and what to say.
 *
 * @param status   {@code "operational"} or {@code "degraded"}
 * @param degraded convenience boolean mirroring {@code status}, so the client can branch on one field
 * @param message  banner copy to display while degraded, or {@code null} when operational
 * @param since    when the degradation began, or {@code null} when operational
 */
public record SystemStatusResponse(
        String status,
        boolean degraded,
        String message,
        Instant since
) {
}
