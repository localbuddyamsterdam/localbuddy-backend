package com.localbuddy.wallet;

import java.time.Instant;

/** The booking details rendered onto a wallet pass (Apple or Google). */
public record WalletPassData(
        String bookingReference,
        String experienceTitle,
        String hostName,
        Instant startTime,
        Instant endTime,
        String location,
        int guests
) {
}
