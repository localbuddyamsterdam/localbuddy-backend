package com.localbuddy.newsletter;

import java.time.Instant;
import java.util.UUID;

public record NewsletterSubscriptionResponse(
        UUID id,
        String email,
        NewsletterAudience audience,
        NewsletterSubscriptionStatus status,
        Instant confirmedAt,
        Instant createdAt
) {
    public static NewsletterSubscriptionResponse from(NewsletterSubscription s) {
        return new NewsletterSubscriptionResponse(
                s.getId(), s.getEmail(), s.getAudience(), s.getStatus(), s.getConfirmedAt(), s.getCreatedAt());
    }
}
