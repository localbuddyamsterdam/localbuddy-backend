package com.localbuddy.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Periodically probes the database and turns the result into incident state + operator alerts.
 *
 * <p><b>Why a dedicated thread and not {@code @Scheduled}:</b> the app's shared scheduler runs
 * single-threaded, and during a DB outage every other scheduled job (notification processor, booking
 * expiry, reminders…) blocks up to Hikari's 30s connection-timeout on that one thread. A
 * {@code @Scheduled} monitor would queue behind them and fire late — exactly when it must fire on
 * time. Running on its own single-thread executor guarantees the monitor is never starved by the
 * outage it exists to detect.
 *
 * <p>Transitions use hysteresis: {@code failureThreshold} consecutive failures declare an incident,
 * {@code recoveryThreshold} consecutive successes clear it. That debounces transient blips so a
 * single dropped connection does not page anyone. Exactly one email is sent per transition (plus
 * optional reminders while an incident stays open).
 */
@Component
public class IncidentMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IncidentMonitor.class);
    private static final String COMPONENT = "database";

    private final IncidentProperties properties;
    private final DatabaseProbe databaseProbe;
    private final SystemStatusService statusService;
    private final IncidentAlertService alertService;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    // Touched only on the single monitor thread (fixed-delay guarantees no overlapping runs) → no locking.
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private Instant lastReminderAt;

    public IncidentMonitor(IncidentProperties properties,
                           DatabaseProbe databaseProbe,
                           SystemStatusService statusService,
                           IncidentAlertService alertService) {
        this.properties = properties;
        this.databaseProbe = databaseProbe;
        this.statusService = statusService;
        this.alertService = alertService;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            log.info("IncidentMonitor disabled (app.incident.enabled=false)");
            return;
        }
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "incident-monitor");
            t.setDaemon(true); // must never keep the JVM alive on shutdown
            return t;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
        long delay = Math.max(1000, properties.checkIntervalMs());
        // Small initial delay so the first probe runs after the context is fully up.
        executor.scheduleWithFixedDelay(this::tick, 15_000, delay, TimeUnit.MILLISECONDS);
        running = true;
        log.info("IncidentMonitor started: probing {} every {}ms (failureThreshold={}, recoveryThreshold={})",
                COMPONENT, delay, properties.failureThreshold(), properties.recoveryThreshold());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** One monitor tick. Wrapped so no failure — probe, state, or email — can ever kill the loop. */
    private void tick() {
        try {
            runCheck();
        } catch (Throwable t) {
            log.error("IncidentMonitor tick failed (will retry next interval)", t);
        }
    }

    private void runCheck() {
        ProbeResult result = databaseProbe.probe();
        Instant now = Instant.now();
        statusService.recordProbe(result, now);

        if (result.healthy()) {
            onHealthy(now);
        } else {
            onFailure(result, now);
        }
    }

    private void onHealthy(Instant now) {
        consecutiveFailures = 0;
        consecutiveSuccesses++;
        if (statusService.hasOpenIncident() && consecutiveSuccesses >= properties.recoveryThreshold()) {
            Incident resolved = statusService.closeIncident(now);
            lastReminderAt = null;
            log.info("Incident resolved after {} healthy probe(s): {}", consecutiveSuccesses,
                    resolved == null ? "-" : resolved.id());
            if (resolved != null) {
                alertService.sendIncidentResolved(resolved, now);
            }
        }
    }

    private void onFailure(ProbeResult result, Instant now) {
        consecutiveSuccesses = 0;
        consecutiveFailures++;

        if (!statusService.hasOpenIncident()) {
            if (consecutiveFailures >= properties.failureThreshold()) {
                Incident opened = statusService.openIncident(COMPONENT, result.detail(), now);
                lastReminderAt = now;
                log.warn("Incident opened after {} failed probe(s) [{}]: {}", consecutiveFailures,
                        opened.id(), result.detail());
                alertService.sendIncidentOpened(opened, now);
            }
            return;
        }

        // Incident already open — optionally remind while it persists.
        long reminderMs = properties.reminderIntervalMs();
        if (reminderMs > 0
                && (lastReminderAt == null || Duration.between(lastReminderAt, now).toMillis() >= reminderMs)) {
            lastReminderAt = now;
            alertService.sendIncidentReminder(statusService.currentIncident(), now);
        }
    }
}
