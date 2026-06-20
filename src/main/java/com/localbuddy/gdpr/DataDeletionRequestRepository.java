package com.localbuddy.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DataDeletionRequestRepository extends JpaRepository<DataDeletionRequest, UUID> {

    List<DataDeletionRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<DataDeletionRequest> findByStatusOrderByCreatedAtAsc(DataDeletionStatus status);

    List<DataDeletionRequest> findAllByOrderByCreatedAtDesc();

    boolean existsByUserIdAndStatus(UUID userId, DataDeletionStatus status);
}
