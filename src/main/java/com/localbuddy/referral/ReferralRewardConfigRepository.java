package com.localbuddy.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralRewardConfigRepository extends JpaRepository<ReferralRewardConfig, UUID> {

    /** The single configuration row, if one has been created. */
    Optional<ReferralRewardConfig> findFirstByOrderByCreatedAtAsc();
}
