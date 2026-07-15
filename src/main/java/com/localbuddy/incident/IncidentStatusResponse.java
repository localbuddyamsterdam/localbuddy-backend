package com.localbuddy.incident;

import java.time.Instant;

/**
 * Detailed status for admins ({@code GET /api/admin/system-status}) — the full picture the public
 * banner deliberately hides: the current and last-resolved incident, the latest probe observation,
 * and any manual maintenance banner.
 */
public record IncidentStatusResponse(
        String status,                 // "operational" | "degraded"
        boolean incidentOpen,
        Incident currentIncident,      // null when operational
        Incident lastResolvedIncident, // null if none this process lifetime
        Instant lastCheckedAt,
        boolean lastProbeHealthy,
        String lastProbeDetail,
        long lastProbeLatencyMs,
        boolean manualBannerActive,
        String manualBannerMessage,
        Instant manualBannerSince
) {
}
