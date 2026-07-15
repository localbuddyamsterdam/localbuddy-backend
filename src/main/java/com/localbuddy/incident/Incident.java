package com.localbuddy.incident;

import java.time.Duration;
import java.time.Instant;

/**
 * An immutable snapshot of a detected outage. Created when the monitor declares an incident and
 * copied (with {@code resolvedAt} set) when it clears. Held only in memory — incidents are an
 * operational alerting signal, not persisted domain data (and persistence would defeat the point,
 * since the database is the thing that is usually down).
 *
 * @param id         short opaque id, stable for the life of one incident (used to correlate the
 *                   "opened" and "resolved" emails)
 * @param component  which dependency failed, e.g. {@code "database"}
 * @param reason     the probe detail that triggered detection
 * @param startedAt  when the incident was declared
 * @param resolvedAt when it cleared, or {@code null} while ongoing
 */
public record Incident(
        String id,
        String component,
        String reason,
        Instant startedAt,
        Instant resolvedAt
) {
    public boolean ongoing() {
        return resolvedAt == null;
    }

    /** Elapsed time to resolution, or to {@code now} while still ongoing. */
    public Duration duration(Instant now) {
        return Duration.between(startedAt, resolvedAt == null ? now : resolvedAt);
    }

    public Incident resolve(Instant at) {
        return new Incident(id, component, reason, startedAt, at);
    }
}
