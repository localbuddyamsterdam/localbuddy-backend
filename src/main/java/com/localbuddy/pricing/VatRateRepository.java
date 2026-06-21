package com.localbuddy.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VatRateRepository extends JpaRepository<VatRate, UUID> {

    /**
     * Active, in-window VAT rates for a country and category (pass categoryId=null
     * for the country default), newest first.
     */
    @Query("""
            select v from VatRate v
            where v.active = true
              and v.country = :country
              and ((:categoryId is null and v.categoryId is null) or v.categoryId = :categoryId)
              and v.effectiveFrom <= :now
              and (v.effectiveTo is null or v.effectiveTo > :now)
            order by v.effectiveFrom desc
            """)
    List<VatRate> findActive(@Param("country") String country,
                             @Param("categoryId") UUID categoryId,
                             @Param("now") Instant now);
}
