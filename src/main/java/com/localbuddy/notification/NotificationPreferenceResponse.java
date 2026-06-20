package com.localbuddy.notification;

public record NotificationPreferenceResponse(
        boolean bookingReminders,
        boolean marketingEmails,
        boolean emailEnabled,
        boolean smsEnabled
) {
    public static NotificationPreferenceResponse from(NotificationPreference p) {
        return new NotificationPreferenceResponse(
                p.isBookingReminders(),
                p.isMarketingEmails(),
                p.isEmailEnabled(),
                p.isSmsEnabled()
        );
    }

    public static NotificationPreferenceResponse defaults() {
        return new NotificationPreferenceResponse(true, false, true, false);
    }
}
