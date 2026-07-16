package com.localbuddy.booking;

import com.localbuddy.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByBookingReference(String bookingReference);

    boolean existsByBookingReference(String bookingReference);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "experience", "experience.city", "localProfile", "availabilitySlot", "loggedInUser"})
    List<Booking> findByLoggedInUserIdOrderByRequestedAtDesc(UUID loggedInUserId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "experience", "experience.city", "localProfile", "availabilitySlot", "loggedInUser"})
    List<Booking> findByLocalProfileIdOrderByRequestedAtDesc(UUID localProfileId);

    List<Booking> findByStatusOrderByRequestedAtDesc(BookingStatus status);

    /**
     * Admin console listing — eager-fetches the ToOne associations the admin response denormalizes
     * (experience + its city, host profile, slot, traveller) so the list is a single query, not N+1.
     */
    @org.springframework.data.jpa.repository.Query("SELECT b FROM Booking b ORDER BY b.requestedAt DESC")
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "experience", "experience.city", "localProfile", "availabilitySlot", "loggedInUser"})
    List<Booking> findAllForAdmin();

    @org.springframework.data.jpa.repository.Query(
            "SELECT b FROM Booking b WHERE b.status = :status ORDER BY b.requestedAt DESC")
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "experience", "experience.city", "localProfile", "availabilitySlot", "loggedInUser"})
    List<Booking> findAllForAdminByStatus(BookingStatus status);

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

    // --- Guest-booking account claiming (attach guest bookings to the account that owns the email) ---

    /**
     * Unclaimed guest bookings (no traveller attached yet) whose {@code guestEmail} equals the given
     * value. Guest emails are stored already-normalized to lower-case, so callers pass a normalized
     * email and this uses the plain {@code idx_bookings_guest_email} index.
     */
    List<Booking> findByLoggedInUserIsNullAndGuestEmail(String guestEmail);

    /**
     * Slot ids on which this traveller already holds a booking in one of the given statuses. Used to
     * skip claiming a guest booking that would otherwise collide with the traveller on the
     * {@code ux_bookings_active_traveler_slot} partial-unique index (one active booking per slot).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT b.availabilitySlot.id FROM Booking b "
                    + "WHERE b.loggedInUser.id = :userId AND b.status IN :statuses")
    List<UUID> findActiveSlotIdsForUser(UUID userId, Collection<BookingStatus> statuses);

    /**
     * Bulk-attaches the given bookings to a traveller — sets {@code traveler_user_id} and bumps
     * {@code updated_at} (the {@code @PreUpdate} hook does not run for bulk JPQL updates, so the
     * caller passes the timestamp). Leaves {@code booking_source} and the guest_* fields untouched,
     * so the booking keeps its guest identity (and its emailed pay/check-in/lookup links) while
     * becoming visible under the account.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Booking b SET b.loggedInUser = :user, b.updatedAt = :updatedAt "
                    + "WHERE b.id IN :ids")
    int attachBookingsToUser(User user, Collection<UUID> ids, Instant updatedAt);

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

    /** Booking counts per experience for the given statuses created since a cutoff — trending signal. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT b.experience.id, COUNT(b)
            FROM Booking b
            WHERE b.status IN :statuses
              AND b.createdAt >= :since
            GROUP BY b.experience.id
            """)
    List<Object[]> countBookingsByExperienceSince(Collection<BookingStatus> statuses, Instant since);

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