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
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "localProfile", "localProfile.user", "experience"})
    List<AvailabilitySlot> findUnderbookedSlotsForNotice(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("status") AvailabilityStatus status,
            @Param("minGuests") int minGuests
    );

    /** A host's slots from an instant onward, excluding a status (e.g. CANCELLED) — used for conflict checks. */
    List<AvailabilitySlot> findByLocalProfileIdAndStartTimeGreaterThanEqualAndStatusNot(
            UUID localProfileId, Instant startTime, AvailabilityStatus status);

    /** Future slots produced by a given schedule — used when editing/pausing/deleting the schedule. */
    List<AvailabilitySlot> findBySourceScheduleIdAndStartTimeAfter(UUID sourceScheduleId, Instant startTime);

    /**
     * All seat-available slots of APPROVED experiences in a city within [from, to) — the AI trip
     * planner's inventory query. Experience, host profile, category and city are fetch-joined so
     * callers can build prompt/link data outside a lazy-loading context. Booking-window cutoffs
     * are asymmetric (15/60 min) and must still be applied in Java via BookingWindowPolicy.
     * Only per-guest bookable modes are eligible (buyout-only experiences can't be booked by the
     * planner's per-guest deep links).
     */
    @Query("""
            select slot from AvailabilitySlot slot
            join fetch slot.experience experience
            join fetch experience.localProfile
            join fetch experience.city city
            left join fetch experience.category
            where city.slug = :citySlug
              and experience.status = :experienceStatus
              and experience.bookingMode in :bookingModes
              and slot.status = :slotStatus
              and slot.startTime >= :from
              and slot.startTime < :to
              and slot.bookedCount < slot.capacity
            order by slot.startTime asc
            """)
    List<AvailabilitySlot> findBookableInCityBetween(
            @Param("citySlug") String citySlug,
            @Param("experienceStatus") com.localbuddy.experience.ExperienceStatus experienceStatus,
            @Param("bookingModes") java.util.Collection<com.localbuddy.experience.BookingMode> bookingModes,
            @Param("slotStatus") AvailabilityStatus slotStatus,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}