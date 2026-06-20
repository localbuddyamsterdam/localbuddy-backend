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

    List<Booking> findByTravelerUserIdOrderByRequestedAtDesc(UUID travelerUserId);

    List<Booking> findByLocalProfileIdOrderByRequestedAtDesc(UUID localProfileId);

    List<Booking> findByStatusOrderByRequestedAtDesc(BookingStatus status);

    List<Booking> findByTravelerUserIdAndStatusOrderByRequestedAtDesc(
            UUID travelerUserId,
            BookingStatus status
    );

    List<Booking> findByLocalProfileIdAndStatusOrderByRequestedAtDesc(
            UUID localProfileId,
            BookingStatus status
    );

    boolean existsByTravelerUserIdAndAvailabilitySlotIdAndStatusIn(
            UUID travelerUserId,
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

    long countByStatus(BookingStatus status);

    List<Booking> findTop100ByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(
            BookingStatus status,
            Instant requestedAtBefore
    );
}