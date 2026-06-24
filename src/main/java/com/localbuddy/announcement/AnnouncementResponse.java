package com.localbuddy.announcement;

import java.time.Instant;
import java.util.UUID;

public record AnnouncementResponse(
        UUID id,
        UUID localProfileId,
        AnnouncementAudience audience,
        String subject,
        String body,
        int recipientCount,
        Instant createdAt
) {
    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(
                a.getId(),
                a.getLocalProfile() != null ? a.getLocalProfile().getId() : null,
                a.getAudience(),
                a.getSubject(),
                a.getBody(),
                a.getRecipientCount(),
                a.getCreatedAt());
    }
}
