package com.localbuddy.tripplan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripPlanRepository extends JpaRepository<TripPlan, UUID> {

    Optional<TripPlan> findByToken(String token);
}
