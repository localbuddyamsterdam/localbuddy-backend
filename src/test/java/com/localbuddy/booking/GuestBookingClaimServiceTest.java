package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the claim rules for {@link GuestBookingClaimService}: a verified traveller picks up every
 * unclaimed guest booking made with their email, except an active one that would collide with an
 * active booking they already hold for the same slot ({@code ux_bookings_active_traveler_slot}).
 */
class GuestBookingClaimServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final GuestBookingClaimService service =
            new GuestBookingClaimService(bookingRepository, userRepository);

    private static final String EMAIL = "guest@example.com";

    @Test
    @DisplayName("attaches all guest bookings when the traveller has no clashing active slot")
    void attachesAllWhenNoConflict() {
        UUID userId = UUID.randomUUID();
        UUID slotA = UUID.randomUUID();
        UUID slotB = UUID.randomUUID();
        Booking confirmed = guestBooking(BookingStatus.CONFIRMED, slotA);
        Booking cancelled = guestBooking(BookingStatus.CANCELLED_BY_LOGGED_IN_USER, slotB);
        User ref = new User();

        when(bookingRepository.findByLoggedInUserIsNullAndGuestEmail(EMAIL))
                .thenReturn(List.of(confirmed, cancelled));
        when(bookingRepository.findActiveSlotIdsForUser(eq(userId), any())).thenReturn(List.of());
        when(userRepository.getReferenceById(userId)).thenReturn(ref);
        when(bookingRepository.attachBookingsToUser(eq(ref), anyCollection(), any())).thenReturn(2);

        // Mixed-case / padded input must be normalized to the stored lower-case email.
        int claimed = service.claimGuestBookings(userId, "  Guest@Example.com  ");

        assertEquals(2, claimed);
        verify(bookingRepository).findByLoggedInUserIsNullAndGuestEmail(EMAIL);
        assertEquals(Set.of(confirmed.getId(), cancelled.getId()), capturedClaimedIds(ref));
    }

    @Test
    @DisplayName("skips an active guest booking whose slot the traveller already holds actively")
    void skipsActiveSlotConflictButKeepsTheRest() {
        UUID userId = UUID.randomUUID();
        UUID clashedSlot = UUID.randomUUID();
        UUID freeSlot = UUID.randomUUID();
        Booking activeClash = guestBooking(BookingStatus.CONFIRMED, clashedSlot);      // skipped
        Booking cancelledSameSlot = guestBooking(BookingStatus.EXPIRED, clashedSlot);  // non-active -> kept
        Booking activeElsewhere = guestBooking(BookingStatus.PENDING_PAYMENT, freeSlot); // kept
        User ref = new User();

        when(bookingRepository.findByLoggedInUserIsNullAndGuestEmail(EMAIL))
                .thenReturn(List.of(activeClash, cancelledSameSlot, activeElsewhere));
        when(bookingRepository.findActiveSlotIdsForUser(eq(userId), any()))
                .thenReturn(List.of(clashedSlot));
        when(userRepository.getReferenceById(userId)).thenReturn(ref);
        when(bookingRepository.attachBookingsToUser(eq(ref), anyCollection(), any())).thenReturn(2);

        int claimed = service.claimGuestBookings(userId, EMAIL);

        assertEquals(2, claimed);
        assertEquals(Set.of(cancelledSameSlot.getId(), activeElsewhere.getId()), capturedClaimedIds(ref));
    }

    @Test
    @DisplayName("no candidates: no update, no user lookup")
    void noCandidatesShortCircuits() {
        UUID userId = UUID.randomUUID();
        when(bookingRepository.findByLoggedInUserIsNullAndGuestEmail(EMAIL)).thenReturn(List.of());

        int claimed = service.claimGuestBookings(userId, EMAIL);

        assertEquals(0, claimed);
        verify(bookingRepository, never()).attachBookingsToUser(any(), anyCollection(), any());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("all candidates conflict: nothing attached, no user reference resolved")
    void allConflictsAttachNothing() {
        UUID userId = UUID.randomUUID();
        UUID clashedSlot = UUID.randomUUID();
        Booking onlyClash = guestBooking(BookingStatus.CONFIRMED, clashedSlot);

        when(bookingRepository.findByLoggedInUserIsNullAndGuestEmail(EMAIL))
                .thenReturn(List.of(onlyClash));
        when(bookingRepository.findActiveSlotIdsForUser(eq(userId), any()))
                .thenReturn(List.of(clashedSlot));

        int claimed = service.claimGuestBookings(userId, EMAIL);

        assertEquals(0, claimed);
        verify(bookingRepository, never()).attachBookingsToUser(any(), anyCollection(), any());
        verify(userRepository, never()).getReferenceById(any());
    }

    @Test
    @DisplayName("null/blank inputs are ignored")
    void guardsNullAndBlankInputs() {
        assertEquals(0, service.claimGuestBookings(null, EMAIL));
        assertEquals(0, service.claimGuestBookings(UUID.randomUUID(), null));
        assertEquals(0, service.claimGuestBookings(UUID.randomUUID(), "   "));
        verifyNoInteractions(bookingRepository);
        verifyNoInteractions(userRepository);
    }

    // --- helpers ---

    private static Booking guestBooking(BookingStatus status, UUID slotId) {
        AvailabilitySlot slot = mock(AvailabilitySlot.class);
        when(slot.getId()).thenReturn(slotId);
        Booking booking = mock(Booking.class);
        when(booking.getId()).thenReturn(UUID.randomUUID());
        when(booking.getStatus()).thenReturn(status);
        when(booking.getAvailabilitySlot()).thenReturn(slot);
        return booking;
    }

    private Set<UUID> capturedClaimedIds(User ref) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(bookingRepository).attachBookingsToUser(eq(ref), captor.capture(), any());
        return Set.copyOf(captor.getValue());
    }
}
