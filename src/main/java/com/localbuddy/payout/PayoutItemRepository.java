package com.localbuddy.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayoutItemRepository extends JpaRepository<PayoutItem, UUID> {

    boolean existsByPaymentId(UUID paymentId);
}
