package com.localbuddy.newsletter;

import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A newsletter subscription keyed by email so anonymous visitors and logged-in users alike
 * can subscribe. Uses double opt-in (confirm token) and a one-click unsubscribe token.
 */
@Entity
@Table(name = "newsletter_subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_newsletter_email", columnNames = {"email"}))
@Getter
@Setter
@NoArgsConstructor
public class NewsletterSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Linked user when a logged-in user subscribes; null for anonymous subscribers. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private NewsletterAudience audience = NewsletterAudience.ALL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NewsletterSubscriptionStatus status = NewsletterSubscriptionStatus.PENDING;

    @Column(name = "confirm_token", length = 80)
    private String confirmToken;

    @Column(name = "unsubscribe_token", nullable = false, length = 80)
    private String unsubscribeToken;

    @Column(name = "source", length = 80)
    private String source;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "unsubscribed_at")
    private Instant unsubscribedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
