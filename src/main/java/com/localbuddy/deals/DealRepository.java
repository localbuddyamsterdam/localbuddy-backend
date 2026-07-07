package com.localbuddy.deals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {

    /**
     * Currently-live deals: active and within the (optional) time window, optionally
     * filtered by scope/target. A null filter parameter means "no constraint" on that
     * dimension. Higher priority is returned first, then most recently created.
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.active = true
              AND (d.startsAt IS NULL OR d.startsAt <= :now)
              AND (d.endsAt IS NULL OR d.endsAt >= :now)
              AND (:dealType IS NULL OR d.dealType = :dealType)
              AND (
                    :cityId IS NULL
                    OR d.scope = com.localbuddy.deals.DealScope.GLOBAL
                    OR (d.scope = com.localbuddy.deals.DealScope.CITY AND d.targetCityId = :cityId)
                  )
              AND (
                    :experienceId IS NULL
                    OR d.scope = com.localbuddy.deals.DealScope.GLOBAL
                    OR (d.scope = com.localbuddy.deals.DealScope.EXPERIENCE AND d.targetExperienceId = :experienceId)
                  )
              AND (
                    :categoryId IS NULL
                    OR d.scope = com.localbuddy.deals.DealScope.GLOBAL
                    OR (d.scope = com.localbuddy.deals.DealScope.CATEGORY AND d.targetCategoryId = :categoryId)
                  )
            ORDER BY d.priority DESC, d.createdAt DESC
            """)
    List<Deal> findLiveDeals(
            @Param("now") Instant now,
            @Param("dealType") DealType dealType,
            @Param("cityId") UUID cityId,
            @Param("experienceId") UUID experienceId,
            @Param("categoryId") UUID categoryId
    );

    /**
     * All live deals APPLICABLE to one experience: GLOBAL, or matching the experience's own id,
     * its category, or its city (OR semantics — unlike {@link #findLiveDeals}, which AND-combines
     * independent filters and is meant for one-dimension browsing). The caller ranks by specificity.
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.active = true
              AND (d.startsAt IS NULL OR d.startsAt <= :now)
              AND (d.endsAt IS NULL OR d.endsAt >= :now)
              AND (
                    d.scope = com.localbuddy.deals.DealScope.GLOBAL
                 OR (d.scope = com.localbuddy.deals.DealScope.EXPERIENCE AND d.targetExperienceId = :experienceId)
                 OR (d.scope = com.localbuddy.deals.DealScope.CATEGORY AND d.targetCategoryId = :categoryId)
                 OR (d.scope = com.localbuddy.deals.DealScope.CITY AND d.targetCityId = :cityId)
              )
            """)
    List<Deal> findApplicableForExperience(
            @Param("now") Instant now,
            @Param("experienceId") UUID experienceId,
            @Param("categoryId") UUID categoryId,
            @Param("cityId") UUID cityId
    );
}
