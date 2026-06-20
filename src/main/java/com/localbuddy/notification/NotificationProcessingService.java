package com.localbuddy.notification;

import com.localbuddy.notification.email.EmailProviderService;
import com.localbuddy.notification.email.EmailSendRequest;
import com.localbuddy.notification.email.EmailSendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationProcessingService {

    private final NotificationRepository notificationRepository;
    private final EmailProviderService emailProviderService;

    public NotificationProcessingService(NotificationRepository notificationRepository,
                                         EmailProviderService emailProviderService) {
        this.notificationRepository = notificationRepository;
        this.emailProviderService = emailProviderService;
    }

    @Transactional
    public void processOneNotification(UUID notificationId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElse(null);

        if (notification == null || notification.getStatus() != NotificationStatus.PENDING) {
            return;
        }

        notification.setStatus(NotificationStatus.PROCESSING);
        notification.setUpdatedAt(Instant.now());
        notificationRepository.save(notification);

        try {
            processNotification(notification);
        } catch (Exception ex) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(ex.getMessage());
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
        }
    }

    private void processNotification(Notification notification) {
        if (notification.getChannel() == NotificationChannel.IN_APP) {
            // In-app notifications are "delivered" simply by being persisted;
            // the recipient reads them through the notification feed.
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
            notification.setFailureReason(null);
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
            return;
        }

        if (notification.getChannel() != NotificationChannel.EMAIL) {
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setFailureReason("Notification channel not supported yet: " + notification.getChannel());
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
            return;
        }

        if (notification.getRecipientEmail() == null ||
                notification.getRecipientEmail().trim().isEmpty()) {
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setFailureReason("Recipient email is missing");
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
            return;
        }

        EmailSendResult result = emailProviderService.sendEmail(
                new EmailSendRequest(
                        notification.getRecipientEmail(),
                        notification.getSubject(),
                        notification.getMessage()
                )
        );

        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setFailureReason(null);
            notification.setSentAt(Instant.now());
        } else {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(result.failureReason());
        }

        notification.setUpdatedAt(Instant.now());
        notificationRepository.save(notification);
    }
}