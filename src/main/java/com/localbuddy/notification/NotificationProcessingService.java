package com.localbuddy.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.calendar.CalendarService;
import com.localbuddy.notification.email.EmailProviderService;
import com.localbuddy.notification.email.EmailSendRequest;
import com.localbuddy.notification.email.EmailSendResult;
import com.localbuddy.whatsapp.WhatsAppSendResult;
import com.localbuddy.whatsapp.WhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessingService.class);

    private final NotificationRepository notificationRepository;
    private final EmailProviderService emailProviderService;
    private final WhatsAppService whatsAppService;
    private final CalendarService calendarService;
    private final ObjectMapper objectMapper;

    public NotificationProcessingService(NotificationRepository notificationRepository,
                                         EmailProviderService emailProviderService,
                                         WhatsAppService whatsAppService,
                                         CalendarService calendarService,
                                         ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.emailProviderService = emailProviderService;
        this.whatsAppService = whatsAppService;
        this.calendarService = calendarService;
        this.objectMapper = objectMapper;
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
            log.warn("Notification {} ({}/{}) failed to process", notification.getId(),
                    notification.getChannel(), notification.getNotificationType(), ex);
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

        if (notification.getChannel() == NotificationChannel.WHATSAPP) {
            processWhatsApp(notification);
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

        // Attach a calendar invite (.ics) to booking confirmations so Gmail/Outlook show an
        // event card at the top of the email. Best-effort — never blocks the send.
        String icsContent = null;
        if (notification.getNotificationType() == NotificationType.BOOKING_CONFIRMED
                && notification.getRelatedEntityId() != null) {
            try {
                icsContent = calendarService.buildInviteIcs(notification.getRelatedEntityId());
            } catch (Exception ignored) {
                icsContent = null;
            }
        }

        EmailSendResult result = emailProviderService.sendEmail(
                new EmailSendRequest(
                        notification.getRecipientEmail(),
                        notification.getSubject(),
                        notification.getMessage(),
                        notification.getHtmlBody(),
                        icsContent
                )
        );

        if (result.success()) {
            notification.setStatus(NotificationStatus.SENT);
            notification.setProviderMessageId(result.providerMessageId());
            notification.setFailureReason(null);
            notification.setSentAt(Instant.now());
        } else {
            // The email provider catches its own exceptions and returns success=false rather than
            // throwing, so this is the only place a send failure is ever visible — log it, or it
            // only ever surfaces as a FAILED row nobody is looking at.
            log.warn("Email send failed for notification {} ({}) to {}: {}",
                    notification.getId(), notification.getNotificationType(),
                    notification.getRecipientEmail(), result.failureReason());
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(result.failureReason());
        }

        notification.setUpdatedAt(Instant.now());
        notificationRepository.save(notification);
    }

    private void processWhatsApp(Notification notification) {
        if (!whatsAppService.isConfigured()) {
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setFailureReason("WhatsApp Business API is not configured");
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
            return;
        }

        if (notification.getRecipientPhone() == null || notification.getRecipientPhone().trim().isEmpty()) {
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setFailureReason("Recipient phone is missing");
            notification.setUpdatedAt(Instant.now());
            notificationRepository.save(notification);
            return;
        }

        // Template messages are the only kind Meta delivers business-initiated (outside an open
        // 24h customer-service session); free-form text remains as the in-session fallback for
        // notifications created without a template (e.g. before Meta approves them).
        WhatsAppSendResult result;
        if (notification.getWaTemplate() != null && !notification.getWaTemplate().isBlank()) {
            result = whatsAppService.sendTemplate(
                    notification.getRecipientPhone(),
                    notification.getWaTemplate(),
                    parseWaParams(notification.getWaParams()));
        } else {
            result = whatsAppService.sendMessage(
                    notification.getRecipientPhone(), notification.getMessage());
        }

        notification.setStatus(NotificationStatus.SENT);
        notification.setProviderMessageId(result.providerMessageId());
        notification.setFailureReason(null);
        notification.setSentAt(Instant.now());
        notification.setUpdatedAt(Instant.now());
        notificationRepository.save(notification);
    }

    /** Stored JSON array of template body params; null/unparseable → send the template bare. */
    private List<String> parseWaParams(String waParamsJson) {
        if (waParamsJson == null || waParamsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(waParamsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("Unparseable wa_params, sending template without parameters: {}", waParamsJson);
            return List.of();
        }
    }
}