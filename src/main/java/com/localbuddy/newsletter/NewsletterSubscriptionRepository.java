package com.localbuddy.newsletter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NewsletterSubscriptionRepository extends JpaRepository<NewsletterSubscription, UUID> {

    Optional<NewsletterSubscription> findByEmailIgnoreCase(String email);

    Optional<NewsletterSubscription> findByConfirmToken(String confirmToken);

    Optional<NewsletterSubscription> findByUnsubscribeToken(String unsubscribeToken);

    List<NewsletterSubscription> findByStatusAndAudienceInOrderByCreatedAtAsc(
            NewsletterSubscriptionStatus status, Collection<NewsletterAudience> audiences);

    List<NewsletterSubscription> findAllByOrderByCreatedAtDesc();

    long countByStatus(NewsletterSubscriptionStatus status);
}
