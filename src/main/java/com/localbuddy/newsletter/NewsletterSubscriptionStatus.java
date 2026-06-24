package com.localbuddy.newsletter;

/** Double-opt-in lifecycle of a newsletter subscription. */
public enum NewsletterSubscriptionStatus {
    /** Subscribed, awaiting email confirmation (double opt-in). */
    PENDING,
    /** Confirmed; receives broadcasts. */
    CONFIRMED,
    /** Opted out via the one-click unsubscribe link. */
    UNSUBSCRIBED
}
