package com.localbuddy.incident;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The single in-memory source of truth for platform status. Read by the public banner endpoint and
 * the admin status endpoint; written only by {@link IncidentMonitor} (auto-detected incidents) and
 * by admins toggling a manual maintenance banner.
 *
 * <p>Intentionally holds no database state: it must remain readable and writable while Postgres is
 * down, which is precisely when it matters. All access is guarded by the intrinsic lock; the guarded
 * sections are trivial field updates, and the (potentially 30s) probe itself runs <em>outside</em>
 * this lock in {@link IncidentMonitor}, so a request reading status is never blocked by a probe.
 */
@Service
public class SystemStatusService {

    private final IncidentProperties properties;

    // ---- auto-detected incident state (owned by the monitor) ----
    private SystemStatus autoStatus = SystemStatus.OPERATIONAL;
    private Incident currentIncident;      // non-null iff autoStatus == DEGRADED
    private Incident lastResolvedIncident; // for the admin view / post-mortem

    // ---- last probe observation (for the admin view) ----
    private Instant lastCheckedAt;
    private boolean lastProbeHealthy = true;
    private String lastProbeDetail = "not yet checked";
    private long lastProbeLatencyMs;

    // ---- manual maintenance banner (admin-toggled, independent of auto-detection) ----
    private String manualBannerMessage;
    private Instant manualBannerSince;

    public SystemStatusService(IncidentProperties properties) {
        this.properties = properties;
    }

    /** Record the outcome of a probe. Does not itself change status — the monitor decides transitions. */
    public synchronized void recordProbe(ProbeResult result, Instant at) {
        this.lastCheckedAt = at;
        this.lastProbeHealthy = result.healthy();
        this.lastProbeDetail = result.detail();
        this.lastProbeLatencyMs = result.latencyMs();
    }

    /** Declare an incident. If one is already open it is kept (idempotent). Returns the open incident. */
    public synchronized Incident openIncident(String component, String reason, Instant at) {
        if (autoStatus == SystemStatus.DEGRADED && currentIncident != null) {
            return currentIncident;
        }
        this.currentIncident = new Incident(shortId(), component, reason, at, null);
        this.autoStatus = SystemStatus.DEGRADED;
        return currentIncident;
    }

    /** Resolve the open incident, if any. Returns the resolved snapshot, or {@code null} if none was open. */
    public synchronized Incident closeIncident(Instant at) {
        this.autoStatus = SystemStatus.OPERATIONAL;
        if (currentIncident == null) {
            return null;
        }
        Incident resolved = currentIncident.resolve(at);
        this.lastResolvedIncident = resolved;
        this.currentIncident = null;
        return resolved;
    }

    public synchronized boolean hasOpenIncident() {
        return autoStatus == SystemStatus.DEGRADED && currentIncident != null;
    }

    public synchronized Incident currentIncident() {
        return currentIncident;
    }

    // ---- manual banner control ----

    public synchronized void raiseManualBanner(String message, Instant at) {
        // A blank message with active=true still means "show a banner" — fall back to the configured copy
        // so the admin never gets a silent no-op.
        this.manualBannerMessage = (message == null || message.isBlank())
                ? properties.bannerMessage()
                : message.trim();
        this.manualBannerSince = at;
    }

    public synchronized void clearManualBanner() {
        this.manualBannerMessage = null;
        this.manualBannerSince = null;
    }

    // ---- views ----

    /**
     * DB-free banner payload. The site is shown as degraded if either an incident is open <em>or</em>
     * an admin has raised a manual maintenance banner; a real incident's copy wins when both are set.
     */
    public synchronized SystemStatusResponse publicView() {
        boolean incidentOpen = hasOpenIncident();
        boolean manualOpen = manualBannerMessage != null;

        if (!incidentOpen && !manualOpen) {
            return new SystemStatusResponse("operational", false, null, null);
        }
        String message = incidentOpen ? properties.bannerMessage() : manualBannerMessage;
        Instant since = incidentOpen ? currentIncident.startedAt() : manualBannerSince;
        return new SystemStatusResponse("degraded", true, message, since);
    }

    public synchronized IncidentStatusResponse adminView() {
        return new IncidentStatusResponse(
                autoStatus.name().toLowerCase(),
                hasOpenIncident(),
                currentIncident,
                lastResolvedIncident,
                lastCheckedAt,
                lastProbeHealthy,
                lastProbeDetail,
                lastProbeLatencyMs,
                manualBannerMessage != null,
                manualBannerMessage,
                manualBannerSince
        );
    }

    private static String shortId() {
        return "inc_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
