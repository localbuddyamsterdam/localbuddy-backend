package com.localbuddy.notification;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class NotificationProcessor {

    private static final int BATCH_SIZE = 20;

    private final NotificationRepository notificationRepository;
    private final NotificationProcessingService notificationProcessingService;

    public NotificationProcessor(NotificationRepository notificationRepository,
                                 NotificationProcessingService notificationProcessingService) {
        this.notificationRepository = notificationRepository;
        this.notificationProcessingService = notificationProcessingService;
    }

    @Scheduled(fixedDelayString = "${app.notifications.processor-delay-ms:10000}")
    public void processPendingNotifications() {
        List<UUID> notificationIds = notificationRepository
                .findPendingNotificationIds(NotificationStatus.PENDING, PageRequest.of(0, BATCH_SIZE));

        for (UUID notificationId : notificationIds) {
            notificationProcessingService.processOneNotification(notificationId);
        }
    }
}