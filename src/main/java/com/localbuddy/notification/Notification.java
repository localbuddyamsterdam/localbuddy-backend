package com.localbuddy.notification;

import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private User recipientUser;

    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(name = "recipient_phone", length = 40)
    private String recipientPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 40)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 80)
    private NotificationType notificationType;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    /** Rendered HTML body for email notifications; null for plain-text-only / other channels. */
    @Column(name = "html_body", columnDefinition = "TEXT")
    private String htmlBody;

    /**
     * Meta-approved WhatsApp template name for WHATSAPP-channel notifications. When set, the
     * processor sends this template (deliverable business-initiated); when null it falls back to
     * free-form text, which Meta only delivers inside an open 24h customer-service session.
     */
    @Column(name = "wa_template", length = 120)
    private String waTemplate;

    /** JSON array of strings filling the template's {{1}}..{{n}} body placeholders, in order. */
    @Column(name = "wa_params", length = 2000)
    private String waParams;

    /** JSON array of dynamic URL suffixes for the template's URL buttons, in button-index order. */
    @Column(name = "wa_button_params", length = 500)
    private String waButtonParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 255)
    private String dedupeKey;

    @Column(name = "related_entity_type", length = 80)
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private UUID relatedEntityId;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (status == null) {
            status = NotificationStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}