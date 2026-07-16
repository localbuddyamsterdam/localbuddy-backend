package com.localbuddy.tripplan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripPlanRepository extends JpaRepository<TripPlan, UUID> {

    Optional<TripPlan> findByToken(String token);

    Page<TripPlan> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
