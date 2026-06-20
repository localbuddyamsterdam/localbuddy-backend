package com.localbuddy.giftcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GiftCardRedemptionRepository extends JpaRepository<GiftCardRedemption, UUID> {

    List<GiftCardRedemption> findByGiftCardIdOrderByCreatedAtDesc(UUID giftCardId);
}
