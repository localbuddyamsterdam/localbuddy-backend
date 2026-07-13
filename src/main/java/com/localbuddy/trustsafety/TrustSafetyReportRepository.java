package com.localbuddy.trustsafety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrustSafetyReportRepository extends JpaRepository<TrustSafetyReport, UUID> {

    List<TrustSafetyReport> findByStatusOrderByCreatedAtDesc(SafetyReportStatus status);

    List<TrustSafetyReport> findAllByOrderByCreatedAtDesc();

    List<TrustSafetyReport> findByReporterUserIdOrderByCreatedAtDesc(UUID reporterUserId);

    List<TrustSafetyReport> findByReportedUserIdOrderByCreatedAtDesc(UUID reportedUserId);

    /** Count of reports in a given status — admin dashboard trust & safety queue. */
    long countByStatus(SafetyReportStatus status);
}