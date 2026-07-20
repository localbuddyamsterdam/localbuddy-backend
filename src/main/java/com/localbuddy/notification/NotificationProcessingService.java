package com.localbuddy.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localbuddy.booking.BookingRepository;
import com.localbuddy.booking.BookingStatus;
import com.localbuddy.calendar.CalendarService;
import com.localbuddy.notification.email.EmailProviderService;
import com.localbuddy.notification.email.EmailSendRequest;
import com.localbuddy.notification.email.EmailSendResult;
import com.localbuddy.payment.PaymentGroupRepository;
import com.localbuddy.payment.PaymentGroupStatus;
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

    /** Dedupe-key prefixes of the traveler/guest "complete payment" nudges — see isStalePendingPaymentNudge. */
    private static final String TRAVELER_PENDING_PAYMENT_PREFIX = "BOOKING_CREATED:TRAVELER:";
    private static final String GUEST_PENDING_PAYMENT_PREFIX = "GUEST_BOOKING_CREATED:";
    private static final String GROUP_PENDING_PAYMENT_PREFIX = "BOOKING_CREATED:GROUP:";

    private final NotificationRepository notificationRepository;
    private final EmailProviderService emailProviderService;
    private final WhatsAppService whatsAppService;
    private final CalendarService calendarService;
    private final ObjectMapper objectMapper;
    private final BookingRepository bookingRepository;
    private final PaymentGroupRepository paymentGroupRepository;

    public NotificationProcessingService(NotificationRepository notificationRepository,
                                         EmailProviderService emailProviderService,
                                         WhatsAppService whatsAppService,
                                         CalendarService calendarService,
                                         ObjectMapper objectMapper,
                                         BookingRepository bookingRepository,
                                         PaymentGroupRepository paymentGroupRepository) {
        this.notificationRepository = notificationRepository;
        this.emailProviderService = emailProviderService;
        this.whatsAppService = whatsAppService;
        this.calendarService = calendarService;
        this.objectMapper = objectMapper;
        this.bookingRepository = bookingRepository;
        this.paymentGroupRepository = paymentGroupRepository;
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

        if (isStalePendingPaymentNudge(notification)) {
            notification.setStatus(NotificationStatus.SKIPPED);
            notification.setFailureReason("Booking already confirmed before this payment reminder was due to send");
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

    /**
     * The traveler/guest "complete payment" nudge is created the instant a booking (or bundle)
     * exists — before the customer has necessarily paid. By the time this ~10s-polled row is
     * actually sent, a fast payer (saved card, Apple Pay) may already be done, and sending it
     * after the fact reads as a broken confirmation. Skip it once the underlying booking/payment
     * group has moved past "awaiting payment". The host's own copy of BOOKING_CREATED
     * ("BOOKING_CREATED:LOCAL:...") is unaffected — it stays informational regardless of timing.
     */
    private boolean isStalePendingPaymentNudge(Notification notification) {
        String dedupeKey = notification.getDedupeKey();
        UUID relatedEntityId = notification.getRelatedEntityId();
        if (dedupeKey == null || relatedEntityId == null) {
            return false;
        }
        if (dedupeKey.startsWith(TRAVELER_PENDING_PAYMENT_PREFIX) || dedupeKey.startsWith(GUEST_PENDING_PAYMENT_PREFIX)) {
            return bookingRepository.findById(relatedEntityId)
                    .map(booking -> booking.getStatus() != BookingStatus.PENDING_PAYMENT)
                    .orElse(false);
        }
        if (dedupeKey.startsWith(GROUP_PENDING_PAYMENT_PREFIX)) {
            return paymentGroupRepository.findById(relatedEntityId)
                    .map(group -> group.getStatus() != PaymentGroupStatus.PENDING
                            && group.getStatus() != PaymentGroupStatus.PROCESSING)
                    .orElse(false);
        }
        return false;
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
                    parseWaParams(notification.getWaParams()),
                    parseWaParams(notification.getWaButtonParams()));
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