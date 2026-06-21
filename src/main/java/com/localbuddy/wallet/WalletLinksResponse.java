package com.localbuddy.wallet;

/** Availability + links for adding a booking to a mobile wallet. */
public record WalletLinksResponse(
        boolean googleConfigured,
        String googleSaveUrl,
        boolean appleConfigured,
        String applePassPath
) {
}
