package com.localbuddy.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByLocalProfileIdOrderByCreatedAtDesc(UUID localProfileId);

    List<Payout> findAllByOrderByCreatedAtDesc();

    /** Count of payouts across several statuses — admin dashboard payouts queue (PENDING + FAILED). */
    long countByStatusIn(Collection<PayoutStatus> statuses);
}
