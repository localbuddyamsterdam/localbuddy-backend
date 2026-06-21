package com.localbuddy.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RateChangeAuditRepository extends JpaRepository<RateChangeAudit, UUID> {

    List<RateChangeAudit> findByRateTypeOrderByChangedAtDesc(String rateType);
}
