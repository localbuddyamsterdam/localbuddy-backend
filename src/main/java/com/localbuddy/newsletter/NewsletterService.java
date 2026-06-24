package com.localbuddy.newsletter;

import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Newsletter subscriptions with double opt-in and one-click unsubscribe. Sends flow through the
 * existing notification outbox (async, deduped) via {@link NotificationService}, so they work in
 * console mode locally and respect the configured email provider in production.
 */
@Service
public class NewsletterService {

    private final NewsletterSubscriptionRepository repository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final String frontendBaseUrl;

    public NewsletterService(NewsletterSubscriptionRepository repository,
                             NotificationService notificationService,
                             UserRepository userRepository,
                             @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** Anonymous (public) subscribe — always double opt-in. */
    @Transactional
    public NewsletterSubscriptionResponse subscribePublic(SubscribeNewsletterRequest request) {
        return subscribe(request.email(), request.audience(), request.source(), null, false);
    }

    /** Logged-in subscribe — links the user, segments by role, and skips confirmation if email is verified. */
    @Transactional
    public NewsletterSubscriptionResponse subscribeAsUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        NewsletterAudience audience = user.getRole() == UserRole.LOCAL
                ? NewsletterAudience.HOST : NewsletterAudience.TRAVELER;
        return subscribe(user.getEmail(), audience, "account", user, user.isEmailVerified());
    }

    @Transactional(readOnly = true)
    public Optional<NewsletterSubscriptionResponse> getMyStatus(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return repository.findByEmailIgnoreCase(user.getEmail()).map(NewsletterSubscriptionResponse::from);
    }

    @Transactional
    public NewsletterSubscriptionResponse confirm(String token) {
        NewsletterSubscription sub = repository.findByConfirmToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired confirmation link"));
        if (sub.getStatus() != NewsletterSubscriptionStatus.CONFIRMED) {
            sub.setStatus(NewsletterSubscriptionStatus.CONFIRMED);
            sub.setConfirmedAt(Instant.now());
            repository.save(sub);
        }
        return NewsletterSubscriptionResponse.from(sub);
    }

    @Transactional
    public void unsubscribe(String token) {
        NewsletterSubscription sub = repository.findByUnsubscribeToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid unsubscribe link"));
        if (sub.getStatus() != NewsletterSubscriptionStatus.UNSUBSCRIBED) {
            sub.setStatus(NewsletterSubscriptionStatus.UNSUBSCRIBED);
            sub.setUnsubscribedAt(Instant.now());
            repository.save(sub);
        }
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<NewsletterSubscriptionResponse> listAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(NewsletterSubscriptionResponse::from).toList();
    }

    /** Sends a broadcast to all CONFIRMED subscribers in the target audience. Returns recipient count. */
    @Transactional
    public int broadcast(NewsletterBroadcastRequest request) {
        List<NewsletterAudience> targets = switch (request.audience()) {
            case ALL -> List.of(NewsletterAudience.TRAVELER, NewsletterAudience.HOST, NewsletterAudience.ALL);
            case TRAVELER -> List.of(NewsletterAudience.TRAVELER, NewsletterAudience.ALL);
            case HOST -> List.of(NewsletterAudience.HOST, NewsletterAudience.ALL);
        };
        UUID broadcastId = UUID.randomUUID();
        List<NewsletterSubscription> recipients = repository
                .findByStatusAndAudienceInOrderByCreatedAtAsc(NewsletterSubscriptionStatus.CONFIRMED, targets);

        for (NewsletterSubscription sub : recipients) {
            String body = request.body()
                    + "\n\n—\nYou're receiving this because you subscribed to the LocalBuddy newsletter."
                    + "\nUnsubscribe: " + frontendBaseUrl + "/newsletter/unsubscribe?token=" + sub.getUnsubscribeToken();
            notificationService.createEmailNotificationForGuest(
                    sub.getEmail(), null, NotificationType.NEWSLETTER, request.subject(), body,
                    "NEWSLETTER", sub.getId(), "newsletter-blast:" + broadcastId + ":" + sub.getId());
        }
        return recipients.size();
    }

    // ------------------------------------------------------------------ helpers

    private NewsletterSubscriptionResponse subscribe(String rawEmail, NewsletterAudience audience,
                                                     String source, User user, boolean preConfirmed) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        NewsletterSubscription sub = repository.findByEmailIgnoreCase(email)
                .orElseGet(NewsletterSubscription::new);

        sub.setEmail(email);
        sub.setAudience(audience == null ? NewsletterAudience.ALL : audience);
        if (user != null) {
            sub.setUser(user);
        }
        if (source != null && !source.isBlank()) {
            sub.setSource(source.trim());
        }
        if (sub.getUnsubscribeToken() == null) {
            sub.setUnsubscribeToken(UUID.randomUUID().toString());
        }

        if (sub.getStatus() == NewsletterSubscriptionStatus.CONFIRMED) {
            return NewsletterSubscriptionResponse.from(repository.save(sub));
        }

        if (preConfirmed) {
            sub.setStatus(NewsletterSubscriptionStatus.CONFIRMED);
            sub.setConfirmedAt(Instant.now());
            return NewsletterSubscriptionResponse.from(repository.save(sub));
        }

        sub.setStatus(NewsletterSubscriptionStatus.PENDING);
        sub.setConfirmToken(UUID.randomUUID().toString());
        NewsletterSubscription saved = repository.save(sub);
        sendConfirmEmail(saved);
        return NewsletterSubscriptionResponse.from(saved);
    }

    private void sendConfirmEmail(NewsletterSubscription sub) {
        String body = "Thanks for subscribing to the LocalBuddy newsletter.\n\n"
                + "Please confirm your subscription:\n"
                + frontendBaseUrl + "/newsletter/confirm?token=" + sub.getConfirmToken()
                + "\n\nIf you didn't request this, you can safely ignore this email.";
        notificationService.createEmailNotificationForGuest(
                sub.getEmail(), null, NotificationType.NEWSLETTER_CONFIRM,
                "Confirm your LocalBuddy newsletter subscription", body,
                "NEWSLETTER", sub.getId(), "newsletter-confirm:" + sub.getConfirmToken());
    }
}
