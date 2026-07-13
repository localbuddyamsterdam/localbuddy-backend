package com.localbuddy.tripsafety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripSafetyEventRepository extends JpaRepository<TripSafetyEvent, UUID> {

    List<TripSafetyEvent> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<TripSafetyEvent> findByEventTypeAndResolvedFalseOrderByCreatedAtAsc(TripSafetyEventType eventType);

    /** Count of unresolved events of a given type — admin dashboard SOS queue. */
    long countByEventTypeAndResolvedFalse(TripSafetyEventType eventType);
}
