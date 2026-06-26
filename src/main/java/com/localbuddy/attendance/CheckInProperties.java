package com.localbuddy.attendance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Geo check-in tuning. All distances in metres, all windows in minutes.
 * Defaults: 15 min before/after start, 300 m geofence, 500 m worst trusted accuracy, 90-day retention.
 */
@ConfigurationProperties(prefix = "app.checkin")
public record CheckInProperties(
        int beforeMinutes,
        int afterMinutes,
        double geofenceRadiusMeters,
        double maxAccuracyMeters,
        int retentionDays,
        long retentionProcessorDelayMs
) {
}
