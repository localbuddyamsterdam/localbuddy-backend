package com.localbuddy.notification;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferenceRequest(
        @NotNull Boolean bookingReminders,
        @NotNull Boolean marketingEmails,
        @NotNull Boolean emailEnabled,
        @NotNull Boolean smsEnabled
) {
}
