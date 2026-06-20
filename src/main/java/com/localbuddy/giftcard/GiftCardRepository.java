package com.localbuddy.giftcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GiftCardRepository extends JpaRepository<GiftCard, UUID> {

    Optional<GiftCard> findByCode(String code);

    boolean existsByCode(String code);

    List<GiftCard> findByPurchaserUserIdOrderByCreatedAtDesc(UUID purchaserUserId);

    List<GiftCard> findAllByOrderByCreatedAtDesc();
}
