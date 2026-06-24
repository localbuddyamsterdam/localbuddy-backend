package com.localbuddy.announcement;

import java.time.Instant;
import java.util.UUID;

public record FollowedHostResponse(
        UUID localProfileId,
        String displayName,
        Instant followedAt
) {
    public static FollowedHostResponse from(HostFollow f) {
        return new FollowedHostResponse(
                f.getLocalProfile().getId(),
                f.getLocalProfile().getDisplayName(),
                f.getCreatedAt());
    }
}
