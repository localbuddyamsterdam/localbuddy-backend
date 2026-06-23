package com.localbuddy.noshow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NoShowReportRepository extends JpaRepository<NoShowReport, UUID> {

    List<NoShowReport> findByStatusOrderByCreatedAtAsc(NoShowReportStatus status);

    List<NoShowReport> findAllByOrderByCreatedAtDesc();

    List<NoShowReport> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<NoShowReport> findByReportedByUserIdOrderByCreatedAtDesc(UUID reportedByUserId);

    /** Blocks a second report about the same party for a booking while one is still open/approved. */
    boolean existsByBookingIdAndSubjectAndStatusIn(
            UUID bookingId, NoShowSubject subject, java.util.Collection<NoShowReportStatus> statuses);

    /** True if any unresolved (REQUESTED) report exists for a booking — used to pause auto-completion. */
    boolean existsByBookingIdAndStatus(UUID bookingId, NoShowReportStatus status);
}
