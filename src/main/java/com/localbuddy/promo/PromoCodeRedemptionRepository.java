package com.localbuddy.promo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PromoCodeRedemptionRepository extends JpaRepository<PromoCodeRedemption, UUID> {

    long countByPromoCodeId(UUID promoCodeId);

    long countByPromoCodeIdAndUserId(UUID promoCodeId, UUID userId);

    long countByPromoCodeIdAndGuestEmailIgnoreCase(UUID promoCodeId, String guestEmail);

    boolean existsByBookingId(UUID bookingId);

    boolean existsByBookingIdAndPromoCodeId(UUID bookingId, UUID promoCodeId);
}