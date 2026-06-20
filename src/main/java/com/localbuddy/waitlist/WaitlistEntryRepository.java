package com.localbuddy.waitlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    List<WaitlistEntry> findByAvailabilitySlotIdAndStatusOrderByCreatedAtAsc(
            UUID availabilitySlotId,
            WaitlistStatus status
    );

    List<WaitlistEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
