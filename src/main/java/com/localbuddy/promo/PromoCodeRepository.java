package com.localbuddy.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {

    Optional<PromoCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Atomic, race-safe increment of the redemption counter (no lost updates). */
    @Modifying
    @Query("update PromoCode p set p.currentRedemptions = p.currentRedemptions + 1 where p.id = :id")
    void incrementRedemptions(@Param("id") UUID id);
}