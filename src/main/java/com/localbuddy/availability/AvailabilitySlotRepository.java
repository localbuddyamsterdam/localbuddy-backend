package com.localbuddy.availability;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, UUID> {

    List<AvailabilitySlot> findByLocalProfileIdOrderByStartTimeAsc(UUID localProfileId);

    List<AvailabilitySlot> findByExperienceIdAndStatusAndStartTimeAfterOrderByStartTimeAsc(
            UUID experienceId,
            AvailabilityStatus status,
            Instant startTime
    );

    List<AvailabilitySlot> findByExperienceIdAndStatusOrderByStartTimeAsc(
            UUID experienceId,
            AvailabilityStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from AvailabilitySlot slot where slot.id = :slotId")
    Optional<AvailabilitySlot> findByIdForUpdate(@Param("slotId") UUID slotId);

    /** Existing slots in a window — used by bulk generation to skip duplicates. */
    List<AvailabilitySlot> findByExperienceIdAndStartTimeBetween(UUID experienceId, Instant from, Instant to);

    @Query("""
            select slot from AvailabilitySlot slot
            where slot.startTime > :windowStart
              and slot.startTime <= :windowEnd
              and slot.status = :status
              and slot.capacity >= :minGuests
              and slot.bookedCount < :minGuests
            order by slot.startTime asc
            """)
    List<AvailabilitySlot> findUnderbookedSlotsForNotice(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("status") AvailabilityStatus status,
            @Param("minGuests") int minGuests
    );
}