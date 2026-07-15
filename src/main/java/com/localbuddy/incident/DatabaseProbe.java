package com.localbuddy.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Actively checks that the application can actually reach Postgres by borrowing a connection from
 * the main pool and running {@code SELECT 1}. This is the signal {@link IncidentMonitor} uses.
 *
 * <p>Why a direct probe rather than reading Actuator's health: it is version-independent (no coupling
 * to Actuator internals), it measures exactly what a real request needs (a usable pooled connection),
 * and it lets us catch and classify the failure ourselves. During a hard outage the borrow blocks up
 * to Hikari's {@code connection-timeout} (30s) and then throws — that bounded block is acceptable
 * because the probe runs on the monitor's own dedicated thread, never a request thread.
 */
@Component
public class DatabaseProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProbe.class);
    private static final String VALIDATION_QUERY = "SELECT 1";

    private final DataSource dataSource;
    private final IncidentProperties properties;

    public DatabaseProbe(DataSource dataSource, IncidentProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public ProbeResult probe() {
        long startNanos = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds());
            statement.execute(VALIDATION_QUERY);
            return new ProbeResult(true, "ok", elapsedMs(startNanos));
        } catch (Exception ex) {
            String detail = describe(ex);
            // DEBUG, not WARN: during an outage this fires every tick and the incident email is the
            // real signal — we don't want to also flood the log with a stack trace each probe.
            log.debug("Database probe failed: {}", detail);
            return new ProbeResult(false, detail, elapsedMs(startNanos));
        }
    }

    private int queryTimeoutSeconds() {
        return (int) Math.max(1, properties.probeTimeoutMs() / 1000);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Exception class + root-cause message, trimmed — enough to tell "pool exhausted" from "auth failed". */
    private static String describe(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null) {
            message = root.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        if (message.length() > 240) {
            message = message.substring(0, 240) + "…";
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }
}
