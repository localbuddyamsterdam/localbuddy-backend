package com.localbuddy.whatsapp;

import com.localbuddy.notification.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps notification types to the Meta-approved <b>utility</b> template names configured under
 * {@code app.whatsapp.templates.*}. A blank name means "no template approved yet" — callers then
 * fall back to a plain WhatsApp notification (free-form text, open-session delivery only), so the
 * pipeline works before and after Meta review without code changes.
 *
 * <p>Expected template body placeholders (create them this way in Meta Business Manager):
 * <ul>
 *   <li><b>booking-confirmed</b>: {{1}} first name, {{2}} experience title, {{3}} date &amp; time,
 *       {{4}} meeting area, {{5}} booking reference</li>
 *   <li><b>booking-reminder</b>: same five placeholders as booking-confirmed</li>
 *   <li><b>booking-cancelled</b>: {{1}} first name, {{2}} experience title, {{3}} booking reference</li>
 * </ul>
 * Only utility templates are mapped here by design — never a marketing category.
 */
@Component
public class WhatsAppTemplates {

    private final String bookingConfirmed;
    private final String bookingReminder;
    private final String bookingCancelled;

    public WhatsAppTemplates(
            @Value("${app.whatsapp.templates.booking-confirmed:}") String bookingConfirmed,
            @Value("${app.whatsapp.templates.booking-reminder:}") String bookingReminder,
            @Value("${app.whatsapp.templates.booking-cancelled:}") String bookingCancelled) {
        this.bookingConfirmed = bookingConfirmed;
        this.bookingReminder = bookingReminder;
        this.bookingCancelled = bookingCancelled;
    }

    /** The configured template for this notification type, or empty when none is set up. */
    public Optional<String> forType(NotificationType type) {
        String name = switch (type) {
            case BOOKING_CONFIRMED -> bookingConfirmed;
            case BOOKING_REMINDER -> bookingReminder;
            case BOOKING_CANCELLED -> bookingCancelled;
            default -> null;
        };
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name.trim());
    }
}
