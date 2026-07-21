package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.consent.ConsentService;
import com.localbuddy.deals.DealService;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import com.localbuddy.messaging.ConversationRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.referral.ReferralService;
import com.localbuddy.safety.BookingSafetyChecklistRepository;
import com.localbuddy.trustsafety.TrustSafetyService;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import com.localbuddy.waitlist.WaitlistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the access scope of {@link BookingService#getBookingById}: SUPPORT is NOT an all-access
 * role. A SUPPORT agent may only read a booking they have been assigned to (one tied to a
 * conversation they participate in); otherwise the booking enumeration leaks every customer's
 * PII and financial data (IDOR / GDPR confidentiality).
 */
class BookingServiceAccessControlTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final LocalProfileRepository localProfileRepository = mock(LocalProfileRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);

    private final BookingService service = new BookingService(
            bookingRepository,
            userRepository,
            mock(ExperienceRepository.class),
            mock(AvailabilitySlotRepository.class),
            localProfileRepository,
            mock(NotificationService.class),
            new com.localbuddy.whatsapp.WhatsAppTemplates("", "", ""),
            mock(ConsentService.class),
            mock(PromoCodeService.class),
            mock(ReferralService.class),
            mock(BookingSafetyChecklistRepository.class),
            mock(PaymentService.class),
            mock(TrustSafetyService.class),
            mock(ApplicationEventPublisher.class),
            mock(BookingReferenceGenerator.class),
            mock(WaitlistService.class),
            mock(AgeBandPricing.class),
            mock(BookingConfirmationNotifier.class),
            conversationRepository,
            mock(DealService.class),
            mock(com.localbuddy.availability.BookingWindowPolicy.class)
    );

    private User user(UUID id, UserRole role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    /** A fully-wired booking so {@code toResponse} can be built without NPEs. */
    private Booking booking(UUID id) {
        LocalProfile localProfile = new LocalProfile();
        localProfile.setId(UUID.randomUUID());
        Experience experience = new Experience();
        experience.setId(UUID.randomUUID());
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.setId(UUID.randomUUID());

        Booking booking = new Booking();
        booking.setId(id);
        booking.setGuestEmail("victim@example.com");
        booking.setLocalProfile(localProfile);
        booking.setExperience(experience);
        booking.setAvailabilitySlot(slot);
        return booking;
    }

    @Test
    @DisplayName("SUPPORT without an assigned conversation cannot read the booking")
    void supportWithoutAssignedConversationIsDenied() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, UserRole.SUPPORT)));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking(bookingId)));
        when(conversationRepository.existsBookingConversationParticipant(bookingId, userId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.getBookingById(userId, bookingId));
    }

    @Test
    @DisplayName("SUPPORT assigned via a booking conversation can read that booking")
    void supportWithAssignedConversationIsAllowed() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, UserRole.SUPPORT)));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking(bookingId)));
        when(conversationRepository.existsBookingConversationParticipant(bookingId, userId)).thenReturn(true);

        BookingResponse response = service.getBookingById(userId, bookingId);

        assertEquals(bookingId, response.id());
    }

    @Test
    @DisplayName("ADMIN keeps unscoped read and never consults the conversation scope")
    void adminReadsAnyBooking() {
        UUID userId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, UserRole.ADMIN)));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking(bookingId)));

        BookingResponse response = service.getBookingById(userId, bookingId);

        assertEquals(bookingId, response.id());
        verifyNoInteractions(conversationRepository);
    }

    @Test
    @DisplayName("host booking list queries only the profile resolved from the caller's principal")
    void hostBookingsAreScopedToTheCallersOwnProfile() {
        UUID callerUserId = UUID.randomUUID();
        UUID callersProfileId = UUID.randomUUID();
        UUID someoneElsesProfileId = UUID.randomUUID();

        LocalProfile callersProfile = new LocalProfile();
        callersProfile.setId(callersProfileId);
        when(localProfileRepository.findByUserId(callerUserId)).thenReturn(Optional.of(callersProfile));
        when(bookingRepository.findByLocalProfileIdOrderByRequestedAtDesc(callersProfileId))
                .thenReturn(List.of());

        service.getHostBookings(callerUserId, null);

        // The profile id must come from the principal, never from the caller — this is the
        // enumeration guard that keeps host A out of host B's bookings.
        verify(bookingRepository).findByLocalProfileIdOrderByRequestedAtDesc(callersProfileId);
        verify(bookingRepository, org.mockito.Mockito.never())
                .findByLocalProfileIdOrderByRequestedAtDesc(someoneElsesProfileId);
    }

    @Test
    @DisplayName("a caller with no local profile is not a host and cannot list host bookings")
    void nonHostCannotListHostBookings() {
        UUID userId = UUID.randomUUID();
        when(localProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getHostBookings(userId, null));

        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("status filter still resolves the host from the principal")
    void hostBookingsWithStatusFilterStayScoped() {
        UUID callerUserId = UUID.randomUUID();
        UUID callersProfileId = UUID.randomUUID();

        LocalProfile callersProfile = new LocalProfile();
        callersProfile.setId(callersProfileId);
        when(localProfileRepository.findByUserId(callerUserId)).thenReturn(Optional.of(callersProfile));
        when(bookingRepository.findByLocalProfileIdAndStatusOrderByRequestedAtDesc(
                callersProfileId, BookingStatus.REQUESTED)).thenReturn(List.of());

        service.getHostBookings(callerUserId, BookingStatus.REQUESTED);

        verify(bookingRepository)
                .findByLocalProfileIdAndStatusOrderByRequestedAtDesc(callersProfileId, BookingStatus.REQUESTED);
    }
}
