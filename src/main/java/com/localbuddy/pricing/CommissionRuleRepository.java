package com.localbuddy.pricing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CommissionRuleRepository extends JpaRepository<CommissionRule, UUID> {

    /**
     * Active, in-window rules for a scope (pass scopeId=null for PLATFORM),
     * newest effective_from first.
     */
    @Query("""
            select r from CommissionRule r
            where r.active = true
              and r.scopeType = :scope
              and ((:scopeId is null and r.scopeId is null) or r.scopeId = :scopeId)
              and r.effectiveFrom <= :now
              and (r.effectiveTo is null or r.effectiveTo > :now)
            order by r.effectiveFrom desc
            """)
    List<CommissionRule> findActive(@Param("scope") ScopeType scope,
                                    @Param("scopeId") UUID scopeId,
                                    @Param("now") Instant now);

    List<CommissionRule> findByScopeTypeOrderByEffectiveFromDesc(ScopeType scopeType);
}
