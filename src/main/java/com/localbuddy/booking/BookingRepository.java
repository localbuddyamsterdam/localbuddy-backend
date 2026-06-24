package com.localbuddy.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    List<Booking> findByLoggedInUserIdOrderByRequestedAtDesc(UUID loggedInUserId);

    List<Booking> findByLocalProfileIdOrderByRequestedAtDesc(UUID localProfileId);

    List<Booking> findByStatusOrderByRequestedAtDesc(BookingStatus status);

    List<Booking> findByLoggedInUserIdAndStatusOrderByRequestedAtDesc(
            UUID loggedInUserId,
            BookingStatus status
    );

    List<Booking> findByLocalProfileIdAndStatusOrderByRequestedAtDesc(
            UUID localProfileId,
            BookingStatus status
    );

    /** Bookings for a host across several statuses — used to resolve announcement recipients. */
    List<Booking> findByLocalProfileIdAndStatusIn(
            UUID localProfileId,
            Collection<BookingStatus> statuses
    );

    /** Has this user ever engaged with this experience (any of the given statuses)? Wishlist-reminder gate. */
    boolean existsByLoggedInUserIdAndExperienceIdAndStatusIn(
            UUID loggedInUserId,
            UUID experienceId,
            Collection<BookingStatus> statuses
    );

    boolean existsByLoggedInUserIdAndAvailabilitySlotIdAndStatusIn(
            UUID loggedInUserId,
            UUID availabilitySlotId,
            Collection<BookingStatus> statuses
    );

    boolean existsByGuestEmailIgnoreCaseAndAvailabilitySlotIdAndStatusIn(
            String guestEmail,
            UUID availabilitySlotId,
            Collection<BookingStatus> statuses
    );

    List<Booking> findByAvailabilitySlotIdAndStatusIn(
            UUID availabilitySlotId,
            Collection<BookingStatus> statuses
    );

    boolean existsByAvailabilitySlotIdAndStatusIn(
            UUID availabilitySlotId,
            Collection<BookingStatus> statuses
    );

    long countByStatus(BookingStatus status);

    List<Booking> findTop100ByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(
            BookingStatus status,
            Instant requestedAtBefore
    );

    /** Bookings whose slot started before the cutoff and are still confirmed — for the auto-complete sweep. */
    List<Booking> findTop100ByStatusAndAvailabilitySlot_StartTimeBeforeOrderByAvailabilitySlot_StartTimeAsc(
            BookingStatus status,
            Instant startTimeBefore
    );

    /** Abandoned (EXPIRED) bookings within a cancelledAt window — for the abandoned-booking reminder sweep. */
    List<Booking> findTop200ByStatusAndCancelledAtBetweenOrderByCancelledAtAsc(
            BookingStatus status,
            Instant from,
            Instant to
    );

    // --- Reliability counters (derived on demand; auto-correct when admins clear a flag) ---

    long countByLocalProfileIdAndStatus(UUID localProfileId, BookingStatus status);

    long countByLoggedInUserIdAndStatus(UUID loggedInUserId, BookingStatus status);

    long countByExperienceIdAndStatus(UUID experienceId, BookingStatus status);

    long countByLoggedInUserIdAndAttendanceOutcome(UUID loggedInUserId, AttendanceOutcome attendanceOutcome);

    /** Host no-shows counted once per slot occurrence, even if several guests reported the same slot. */
    @org.springframework.data.jpa.repository.Query(
            "select count(distinct b.availabilitySlot.id) from Booking b "
            + "where b.localProfile.id = :localProfileId and b.attendanceOutcome = :outcome")
    long countDistinctSlotsByLocalProfileAndOutcome(UUID localProfileId, AttendanceOutcome outcome);

    @org.springframework.data.jpa.repository.Query(
            "select count(distinct b.availabilitySlot.id) from Booking b "
            + "where b.experience.id = :experienceId and b.attendanceOutcome = :outcome")
    long countDistinctSlotsByExperienceAndOutcome(UUID experienceId, AttendanceOutcome outcome);
}