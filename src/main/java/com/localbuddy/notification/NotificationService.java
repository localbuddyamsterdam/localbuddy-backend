package com.localbuddy.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.email.EmailTemplateService;
import com.localbuddy.user.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmailTemplateService emailTemplateService;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository notificationRepository,
                               EmailTemplateService emailTemplateService,
                               ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.emailTemplateService = emailTemplateService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void createEmailNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientUser == null || recipientUser.getEmail() == null) {
            return;
        }

        createNotification(
                recipientUser,
                recipientUser.getEmail(),
                recipientUser.getPhone(),
                NotificationChannel.EMAIL,
                notificationType,
                subject,
                message,
                relatedEntityType,
                relatedEntityId,
                dedupeKey
        );
    }

    @Transactional
    public void createEmailNotificationForGuest(
            String recipientEmail,
            String recipientPhone,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            return;
        }

        createNotification(
                null,
                recipientEmail.trim().toLowerCase(),
                recipientPhone,
                NotificationChannel.EMAIL,
                notificationType,
                subject,
                message,
                relatedEntityType,
                relatedEntityId,
                dedupeKey
        );
    }

    @Transactional
    public void createInAppNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientUser == null) {
            return;
        }

        createNotification(
                recipientUser,
                recipientUser.getEmail(),
                recipientUser.getPhone(),
                NotificationChannel.IN_APP,
                notificationType,
                subject,
                message,
                relatedEntityType,
                relatedEntityId,
                dedupeKey
        );
    }

    /**
     * Creates the same notification on both the email and in-app channels. The
     * dedupe key is suffixed per channel so each channel keeps its own record.
     */
    @Transactional
    public void createEmailAndInAppNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKeyBase
    ) {
        createEmailNotificationForUser(
                recipientUser, notificationType, subject, message,
                relatedEntityType, relatedEntityId, dedupeKeyBase + ":EMAIL"
        );
        createInAppNotificationForUser(
                recipientUser, notificationType, subject, message,
                relatedEntityType, relatedEntityId, dedupeKeyBase + ":INAPP"
        );
    }

    // --- HTML-email overloads: carry a rendered html body alongside the plain-text message ---

    @Transactional
    public void createEmailNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String htmlBody,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientUser == null || recipientUser.getEmail() == null) {
            return;
        }
        createNotification(
                recipientUser, recipientUser.getEmail(), recipientUser.getPhone(),
                NotificationChannel.EMAIL, notificationType, subject, message, htmlBody,
                relatedEntityType, relatedEntityId, dedupeKey
        );
    }

    @Transactional
    public void createEmailNotificationForGuest(
            String recipientEmail,
            String recipientPhone,
            NotificationType notificationType,
            String subject,
            String message,
            String htmlBody,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            return;
        }
        createNotification(
                null, recipientEmail.trim().toLowerCase(), recipientPhone,
                NotificationChannel.EMAIL, notificationType, subject, message, htmlBody,
                relatedEntityType, relatedEntityId, dedupeKey
        );
    }

    @Transactional
    public void createEmailAndInAppNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String htmlBody,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKeyBase
    ) {
        createEmailNotificationForUser(
                recipientUser, notificationType, subject, message, htmlBody,
                relatedEntityType, relatedEntityId, dedupeKeyBase + ":EMAIL"
        );
        createInAppNotificationForUser(
                recipientUser, notificationType, subject, message,
                relatedEntityType, relatedEntityId, dedupeKeyBase + ":INAPP"
        );
    }

    @Transactional
    public void createWhatsAppNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientUser == null || recipientUser.getPhone() == null
                || recipientUser.getPhone().trim().isEmpty()) {
            return;
        }

        createNotification(
                recipientUser,
                recipientUser.getEmail(),
                recipientUser.getPhone(),
                NotificationChannel.WHATSAPP,
                notificationType,
                subject,
                message,
                relatedEntityType,
                relatedEntityId,
                dedupeKey
        );
    }

    @Transactional
    public void createWhatsAppNotificationForGuest(
            String recipientEmail,
            String recipientPhone,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientPhone == null || recipientPhone.trim().isEmpty()) {
            return;
        }

        createNotification(
                null,
                recipientEmail,
                recipientPhone,
                NotificationChannel.WHATSAPP,
                notificationType,
                subject,
                message,
                relatedEntityType,
                relatedEntityId,
                dedupeKey
        );
    }

    // --- WhatsApp template overloads: deliverable business-initiated (outside the 24h session) ---

    /**
     * A WHATSAPP notification carrying a Meta-approved template + body params. The plain-text
     * {@code message} is kept as the stored/fallback body; the processor prefers the template.
     */
    @Transactional
    public void createWhatsAppTemplateNotificationForUser(
            User recipientUser,
            NotificationType notificationType,
            String subject,
            String message,
            String waTemplate,
            List<String> waParams,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientUser == null || recipientUser.getPhone() == null
                || recipientUser.getPhone().trim().isEmpty()) {
            return;
        }
        createNotification(recipientUser, recipientUser.getEmail(), recipientUser.getPhone(),
                NotificationChannel.WHATSAPP, notificationType, subject, message, null,
                waTemplate, toParamsJson(waParams), relatedEntityType, relatedEntityId, dedupeKey);
    }

    @Transactional
    public void createWhatsAppTemplateNotificationForGuest(
            String recipientEmail,
            String recipientPhone,
            NotificationType notificationType,
            String subject,
            String message,
            String waTemplate,
            List<String> waParams,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (recipientPhone == null || recipientPhone.trim().isEmpty()) {
            return;
        }
        createNotification(null, recipientEmail, recipientPhone,
                NotificationChannel.WHATSAPP, notificationType, subject, message, null,
                waTemplate, toParamsJson(waParams), relatedEntityType, relatedEntityId, dedupeKey);
    }

    /** Serializes template params to the stored JSON array; null/empty stays null (no components). */
    private String toParamsJson(List<String> waParams) {
        if (waParams == null || waParams.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(waParams);
        } catch (Exception ex) {
            // Params that can't serialize shouldn't kill the notification — send template bare.
            return null;
        }
    }

    private void createNotification(
            User recipientUser,
            String recipientEmail,
            String recipientPhone,
            NotificationChannel channel,
            NotificationType notificationType,
            String subject,
            String message,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        createNotification(recipientUser, recipientEmail, recipientPhone, channel, notificationType,
                subject, message, null, relatedEntityType, relatedEntityId, dedupeKey);
    }

    private void createNotification(
            User recipientUser,
            String recipientEmail,
            String recipientPhone,
            NotificationChannel channel,
            NotificationType notificationType,
            String subject,
            String message,
            String htmlBody,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        createNotification(recipientUser, recipientEmail, recipientPhone, channel, notificationType,
                subject, message, htmlBody, null, null, relatedEntityType, relatedEntityId, dedupeKey);
    }

    private void createNotification(
            User recipientUser,
            String recipientEmail,
            String recipientPhone,
            NotificationChannel channel,
            NotificationType notificationType,
            String subject,
            String message,
            String htmlBody,
            String waTemplate,
            String waParamsJson,
            String relatedEntityType,
            UUID relatedEntityId,
            String dedupeKey
    ) {
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }

        // Auto-brand: any EMAIL without a bespoke html body gets the branded generic wrapper.
        // Best-effort — a rendering failure falls back to sending the plain-text message.
        String effectiveHtml = htmlBody;
        if (channel == NotificationChannel.EMAIL
                && (effectiveHtml == null || effectiveHtml.isBlank())
                && message != null && !message.isBlank()) {
            try {
                effectiveHtml = emailTemplateService.renderGeneric(subject, message);
            } catch (Exception ignored) {
                effectiveHtml = null;
            }
        }

        Notification notification = new Notification();
        notification.setRecipientUser(recipientUser);
        notification.setRecipientEmail(optionalTrim(recipientEmail));
        notification.setRecipientPhone(optionalTrim(recipientPhone));
        notification.setChannel(channel);
        notification.setNotificationType(notificationType);
        notification.setSubject(optionalTrim(subject));
        notification.setMessage(message.trim());
        notification.setHtmlBody(effectiveHtml);
        notification.setWaTemplate(optionalTrim(waTemplate));
        notification.setWaParams(waParamsJson);
        notification.setStatus(NotificationStatus.PENDING);
        notification.setDedupeKey(dedupeKey);
        notification.setRelatedEntityType(relatedEntityType);
        notification.setRelatedEntityId(relatedEntityId);

        try {
            notificationRepository.save(notification);
        } catch (DataIntegrityViolationException ex) {
            // Another request may have inserted the same dedupe key.
            // Safe to ignore because duplicate notification should not be created.
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(UUID userId) {
        return notificationRepository
                .findByRecipientUserIdAndChannelOrderByCreatedAtDesc(userId, NotificationChannel.IN_APP)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (notification.getRecipientUser() == null ||
                !notification.getRecipientUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Notification not found");
        }

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }

        return toResponse(notification);
    }

    @Transactional
    public void markAllRead(UUID userId) {
        List<Notification> unread = notificationRepository
                .findByRecipientUserIdAndChannelAndReadAtIsNull(userId, NotificationChannel.IN_APP);

        Instant now = Instant.now();
        for (Notification notification : unread) {
            notification.setReadAt(now);
        }

        notificationRepository.saveAll(unread);
    }

    /** Dismiss (delete) one of the user's own in-app notifications. */
    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (notification.getRecipientUser() == null ||
                !notification.getRecipientUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Notification not found");
        }

        notificationRepository.delete(notification);
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getSubject(),
                notification.getMessage(),
                notification.getRelatedEntityType(),
                notification.getRelatedEntityId(),
                notification.getReadAt() != null,
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }

    private String optionalTrim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}