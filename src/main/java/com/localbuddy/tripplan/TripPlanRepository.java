package com.localbuddy.tripplan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripPlanRepository extends JpaRepository<TripPlan, UUID> {

    Optional<TripPlan> findByToken(String token);

    Page<TripPlan> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Share-token page view: bulk update so entity callbacks don't touch updated_at. */
    @Modifying
    @Query("update TripPlan p set p.viewedCount = p.viewedCount + 1, p.lastViewedAt = :now where p.token = :token")
    int bumpViewCount(@Param("token") String token, @Param("now") Instant now);

    /** Auto-archive sweep: trips whose end date has passed stop being ACTIVE. */
    @Modifying(clearAutomatically = true)
    @Query("update TripPlan p set p.status = :archived where p.status = :active and p.endDate < :cutoff")
    int archiveEndedPlans(@Param("archived") TripPlanStatus archived,
                          @Param("active") TripPlanStatus active,
                          @Param("cutoff") LocalDate cutoff);

    // ------------------------------------------------------------------
    // Funnel (cohort = plans created since :from)
    // ------------------------------------------------------------------

    long countByCreatedAtGreaterThanEqual(Instant from);

    @Query("select count(p) from TripPlan p where p.createdAt >= :from and p.viewedCount >= 2")
    long countReViewed(@Param("from") Instant from);

    @Query("select count(p) from TripPlan p where p.createdAt >= :from and p.feedbackHelpful = :helpful")
    long countFeedback(@Param("from") Instant from, @Param("helpful") boolean helpful);

    @Query("select coalesce(sum(p.inputTokens), 0) from TripPlan p where p.createdAt >= :from")
    long sumInputTokens(@Param("from") Instant from);

    @Query("select coalesce(sum(p.outputTokens), 0) from TripPlan p where p.createdAt >= :from")
    long sumOutputTokens(@Param("from") Instant from);

    /** Plans of the cohort for which at least one bundle checkout (payment group) was started. */
    @Query("select count(distinct g.tripPlanId) from PaymentGroup g, TripPlan p "
            + "where g.tripPlanId = p.id and p.createdAt >= :from")
    long countWithCheckoutStarted(@Param("from") Instant from);

    /** Plans of the cohort with at least one PAID bundle checkout. */
    @Query("select count(distinct g.tripPlanId) from PaymentGroup g, TripPlan p "
            + "where g.tripPlanId = p.id and p.createdAt >= :from "
            + "and g.status = com.localbuddy.payment.PaymentGroupStatus.PAID")
    long countWithCheckoutPaid(@Param("from") Instant from);

    /** Per-model quality/cost split for the cohort. */
    interface ModelFunnelRow {
        String getModel();
        long getPlans();
        long getHelpful();
        long getUnhelpful();
        Double getAvgOutputTokens();
    }

    @Query("""
            select p.model as model, count(p) as plans,
                   sum(case when p.feedbackHelpful = true then 1 else 0 end) as helpful,
                   sum(case when p.feedbackHelpful = false then 1 else 0 end) as unhelpful,
                   avg(p.outputTokens) as avgOutputTokens
            from TripPlan p where p.createdAt >= :from
            group by p.model order by count(p) desc
            """)
    List<ModelFunnelRow> funnelByModel(@Param("from") Instant from);
}
