package com.localbuddy.referral;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically settles pending referral rewards: a referrer is only rewarded once
 * the referred booking has COMPLETED (not cancelled). Polling booking status here
 * keeps the reward decoupled from the many booking-completion/cancellation paths.
 */
@Component
public class ReferralRewardProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardProcessor.class);

    private final ReferralService referralService;

    public ReferralRewardProcessor(ReferralService referralService) {
        this.referralService = referralService;
    }

    @Scheduled(fixedDelayString = "${app.referral.processor-delay-ms:300000}")
    public void processReferralRewards() {
        try {
            referralService.settleReferralRewards();
        } catch (Exception ex) {
            log.warn("Referral reward settlement run failed", ex);
        }
    }
}
