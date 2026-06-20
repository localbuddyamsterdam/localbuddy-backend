package com.localbuddy.notification;

import com.localbuddy.common.exception.ResourceNotFoundException;
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

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
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
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Notification notification = new Notification();
        notification.setRecipientUser(recipientUser);
        notification.setRecipientEmail(optionalTrim(recipientEmail));
        notification.setRecipientPhone(optionalTrim(recipientPhone));
        notification.setChannel(channel);
        notification.setNotificationType(notificationType);
        notification.setSubject(optionalTrim(subject));
        notification.setMessage(message.trim());
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