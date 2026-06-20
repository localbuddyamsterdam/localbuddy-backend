package com.localbuddy.payout;

import com.localbuddy.localprofile.LocalProfile;

import java.math.BigDecimal;

/** Abstraction over a Connect-style payout provider (Stripe Connect by default). */
public interface ConnectPayoutProvider {

    boolean isConfigured();

    /** Return the host's connected-account id, creating one if needed. */
    String ensureConnectAccount(LocalProfile host);

    /** Hosted onboarding URL for the connected account. */
    String createOnboardingLink(String connectAccountId);

    /** Transfer funds to the connected account; returns the provider transfer id. */
    String transfer(String connectAccountId, BigDecimal amount, String currency);
}
