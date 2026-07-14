package com.localbuddy.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReferralRedemptionRepository extends JpaRepository<ReferralRedemption, UUID> {

    boolean existsByReferralCodeIdAndReferredUserId(UUID referralCodeId, UUID referredUserId);

    boolean existsByReferralCodeIdAndReferredGuestEmailIgnoreCase(UUID referralCodeId, String referredGuestEmail);

    long countByReferralCodeId(UUID referralCodeId);

    boolean existsByBookingId(UUID bookingId);

    /** Non-cancelled redemptions of a code since {@code since} — used to enforce the monthly cap. */
    long countByReferralCodeIdAndRewardStatusNotAndRedeemedAtGreaterThanEqual(
            UUID referralCodeId, ReferralRewardStatus excludedStatus, Instant since);

    /** All redemptions currently awaiting settlement (used by the scheduled reward processor). */
    List<ReferralRedemption> findByRewardStatus(ReferralRewardStatus rewardStatus);
}