package com.localbuddy.incident;

/**
 * Coarse, user-facing health of the platform as decided by {@link IncidentMonitor}.
 * Deliberately not tied to Actuator's {@code Status} — this is the state that drives the
 * public "we're having difficulties" banner and admin alerts, and it is held purely in
 * memory so it stays readable even while the database (the usual cause of an incident)
 * is unreachable.
 */
public enum SystemStatus {
    OPERATIONAL,
    DEGRADED
}
