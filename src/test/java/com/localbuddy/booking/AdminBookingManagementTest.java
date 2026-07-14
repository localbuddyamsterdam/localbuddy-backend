package com.localbuddy.booking;

import com.localbuddy.availability.AvailabilitySlot;
import com.localbuddy.availability.AvailabilitySlotRepository;
import com.localbuddy.common.exception.BadRequestException;
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
import com.localbuddy.user.UserRepository;
import com.localbuddy.waitlist.WaitlistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the new admin booking-management behaviour on {@link BookingService}: refund overrides on
 * cancel, contact/note edits, attendance flagging, admin complete, and confirmation resend.
 * Pure Mockito — no Spring context or DB.
 */
class AdminBookingManagementTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final AvailabilitySlotRepository availabilitySlotRepository = mock(AvailabilitySlotRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final BookingConfirmationNotifier bookingConfirmationNotifier = mock(BookingConfirmationNotifier.class);

    private final BookingService service = new BookingService(
            bookingRepository,
            mock(UserRepository.class),
            mock(ExperienceRepository.class),
            availabilitySlotRepository,
            mock(LocalProfileRepository.class),
            mock(NotificationService.class),
            mock(ConsentService.class),
            mock(PromoCodeService.class),
            mock(ReferralService.class),
            mock(BookingSafetyChecklistRepository.class),
            paymentService,
            mock(TrustSafetyService.class),
            mock(ApplicationEventPublisher.class),
            mock(BookingReferenceGenerator.class),
            mock(WaitlistService.class),
            mock(AgeBandPricing.class),
            bookingConfirmationNotifier,
            mock(ConversationRepository.class),
            mock(DealService.class)
    );

    /** A fully-wired confirmed booking so {@code toResponse} builds without NPEs. */
    private Booking booking(UUID id, BookingStatus status) {
        LocalProfile localProfile = new LocalProfile();
        localProfile.setId(UUID.randomUUID());
        Experience experience = new Experience();
        experience.setId(UUID.randomUUID());
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.setId(UUID.randomUUID());
        slot.setCapacity(10);
        slot.setBookedCount(2);

        Booking booking = new Booking();
        booking.setId(id);
        booking.setBookingReference("LB-TEST-1");
        booking.setGuestEmail("guest@example.com");
        booking.setLocalProfile(localProfile);
        booking.setExperience(experience);
        booking.setAvailabilitySlot(slot);
        booking.setGuestsCount(2);
        booking.setSeatsBlocked(2);
        booking.setTotalAmount(new BigDecimal("138.00"));
        booking.setStatus(status);
        return booking;
    }

    private void stubFound(Booking booking) {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(availabilitySlotRepository.findByIdForUpdate(booking.getAvailabilitySlot().getId()))
                .thenReturn(Optional.of(booking.getAvailabilitySlot()));
    }

    @Test
    @DisplayName("Cancel with a refund percentage override threads that percentage to the payment service")
    void cancelWithPercentageOverride() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        stubFound(booking);

        service.cancelBookingByAdmin(booking.getId(),
                new AdminCancelBookingRequest("weather", new BigDecimal("50"), null));

        assertEquals(BookingStatus.CANCELLED_BY_ADMIN, booking.getStatus());
        assertNotNull(booking.getCancelledAt());
        verify(paymentService).handleBookingCancellationPayment(
                same(booking), eq(BookingCancellationActor.ADMIN), anyString(), eq(new BigDecimal("50")));
    }

    @Test
    @DisplayName("Cancel with an explicit refund amount converts it to a percentage of the booking total")
    void cancelWithAmountOverride() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        stubFound(booking);

        // 69.00 of 138.00 == 50%
        service.cancelBookingByAdmin(booking.getId(),
                new AdminCancelBookingRequest("goodwill", null, new BigDecimal("69.00")));

        verify(paymentService).handleBookingCancellationPayment(
                same(booking), eq(BookingCancellationActor.ADMIN), anyString(),
                argThat(pct -> pct != null && pct.compareTo(new BigDecimal("50")) == 0));
    }

    @Test
    @DisplayName("Cancel without an override leaves the refund to the policy (null percentage)")
    void cancelWithoutOverrideUsesPolicy() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        stubFound(booking);

        service.cancelBookingByAdmin(booking.getId(),
                new AdminCancelBookingRequest("duplicate booking", null, null));

        verify(paymentService).handleBookingCancellationPayment(
                same(booking), eq(BookingCancellationActor.ADMIN), anyString(), isNull());
    }

    @Test
    @DisplayName("Cancel rejects a booking that is already completed")
    void cancelRejectsNonCancellable() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.COMPLETED);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThrows(BadRequestException.class, () -> service.cancelBookingByAdmin(
                booking.getId(), new AdminCancelBookingRequest("x", null, null)));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("Editing details corrects contact + notes; blank contact is ignored, blank note clears")
    void updateDetails() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        booking.setGuestFirstName("Old");
        booking.setGuestLastName("Name");
        booking.setLocalResponseNote("prior note");
        stubFound(booking);

        BookingResponse response = service.updateBookingDetailsByAdmin(booking.getId(),
                new AdminUpdateBookingRequest("New", "Name", "NEW@Example.com", "  ", "traveller note", ""));

        assertEquals("New", response.guestFirstName());
        assertEquals("Name", response.guestLastName());
        assertEquals("new@example.com", response.guestEmail());   // normalised lower-case
        assertNull(booking.getGuestPhone());                      // "  " blank → contact left untouched
        assertEquals("traveller note", response.travelerNote());
        assertNull(response.localResponseNote());                 // blank string cleared it
    }

    @Test
    @DisplayName("Setting attendance stamps the marked time; NONE clears it")
    void setAttendance() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        stubFound(booking);

        service.setBookingAttendanceByAdmin(booking.getId(),
                new AdminSetAttendanceRequest(AttendanceOutcome.CUSTOMER_NO_SHOW));
        assertEquals(AttendanceOutcome.CUSTOMER_NO_SHOW, booking.getAttendanceOutcome());
        assertNotNull(booking.getNoShowMarkedAt());

        service.setBookingAttendanceByAdmin(booking.getId(),
                new AdminSetAttendanceRequest(AttendanceOutcome.NONE));
        assertEquals(AttendanceOutcome.NONE, booking.getAttendanceOutcome());
        assertNull(booking.getNoShowMarkedAt());
    }

    @Test
    @DisplayName("Admin complete moves a confirmed booking to completed; rejects others")
    void completeBooking() {
        Booking confirmed = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        stubFound(confirmed);
        service.completeBookingByAdmin(confirmed.getId());
        assertEquals(BookingStatus.COMPLETED, confirmed.getStatus());
        assertNotNull(confirmed.getCompletedAt());

        Booking requested = booking(UUID.randomUUID(), BookingStatus.REQUESTED);
        when(bookingRepository.findById(requested.getId())).thenReturn(Optional.of(requested));
        assertThrows(BadRequestException.class, () -> service.completeBookingByAdmin(requested.getId()));
    }

    @Test
    @DisplayName("Resend confirmation re-sends for a confirmed booking and rejects otherwise")
    void resendConfirmation() {
        Booking confirmed = booking(UUID.randomUUID(), BookingStatus.CONFIRMED);
        when(bookingRepository.findById(confirmed.getId())).thenReturn(Optional.of(confirmed));
        service.resendBookingConfirmationByAdmin(confirmed.getId());
        verify(bookingConfirmationNotifier).sendConfirmation(confirmed);

        Booking pending = booking(UUID.randomUUID(), BookingStatus.PENDING_PAYMENT);
        when(bookingRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertThrows(BadRequestException.class, () -> service.resendBookingConfirmationByAdmin(pending.getId()));
    }
}
