package com.localbuddy.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType notificationType,
        String subject,
        String message,
        String relatedEntityType,
        UUID relatedEntityId,
        boolean read,
        Instant readAt,
        Instant createdAt
) {
}
