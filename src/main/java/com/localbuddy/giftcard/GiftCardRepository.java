package com.localbuddy.giftcard;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GiftCardRepository extends JpaRepository<GiftCard, UUID> {

    Optional<GiftCard> findByCode(String code);

    boolean existsByCode(String code);

    List<GiftCard> findByPurchaserUserIdOrderByCreatedAtDesc(UUID purchaserUserId);

    List<GiftCard> findAllByOrderByCreatedAtDesc();

    /** Row-locks the card so concurrent redemptions can't double-spend the balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GiftCard g where g.id = :id")
    Optional<GiftCard> findByIdForUpdate(@Param("id") UUID id);
}
