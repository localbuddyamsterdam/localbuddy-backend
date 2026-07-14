package com.localbuddy.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Tuning for the in-process incident monitor ({@code app.incident.*}).
 *
 * <p>Every value carries a {@link DefaultValue} so the monitor is fully functional even if
 * nothing is set in {@code application.yaml} — alerting must not silently no-op because a
 * property was forgotten. Recipients come from config (never the DB) so an alert can still
 * be addressed while the database is down.
 */
@ConfigurationProperties(prefix = "app.incident")
public record IncidentProperties(

        /** Master switch. When false the monitor thread never starts. */
        @DefaultValue("true") boolean enabled,

        /** Delay between health probes, in ms. */
        @DefaultValue("30000") long checkIntervalMs,

        /** Consecutive failed probes required before an incident is declared (hysteresis, anti-flap). */
        @DefaultValue("2") int failureThreshold,

        /** Consecutive healthy probes required before an open incident is auto-resolved. */
        @DefaultValue("2") int recoveryThreshold,

        /** Per-probe query timeout in ms (the connection wait is still bounded by Hikari's connection-timeout). */
        @DefaultValue("5000") long probeTimeoutMs,

        /** While an incident is open, re-send a reminder email at most this often (ms). 0 disables reminders. */
        @DefaultValue("0") long reminderIntervalMs,

        /** Explicit alert recipients. When empty, {@code app.support.email} is used as the fallback. */
        List<String> alertEmails,

        /** Copy shown in the public banner during an incident. */
        @DefaultValue("We're experiencing some technical difficulties and are working to restore full "
                + "service. Some features may be temporarily unavailable — please try again shortly.")
        String bannerMessage
) {
    public IncidentProperties {
        alertEmails = (alertEmails == null) ? List.of() : List.copyOf(alertEmails);
    }
}
