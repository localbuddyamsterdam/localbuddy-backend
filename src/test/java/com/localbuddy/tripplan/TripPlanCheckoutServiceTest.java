package com.localbuddy.tripplan;

import com.localbuddy.booking.BookingResponse;
import com.localbuddy.booking.BookingService;
import com.localbuddy.booking.CreateBookingRequest;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.payment.PaymentGroupResponse;
import com.localbuddy.payment.PaymentGroupStatus;
import com.localbuddy.payment.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins "book my trip" orchestration: each selected item books through the normal
 * BookingService path; an item that fails (slot sold out, duplicate, …) is SKIPPED and
 * reported while the rest proceed to ONE payment group; when nothing can be booked the
 * request fails; and only bookable items of the plan are accepted at all.
 */
class TripPlanCheckoutServiceTest {

    private final TripPlanService tripPlanService = mock(TripPlanService.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final PaymentService paymentService = mock(PaymentService.class);

    private final TripPlanCheckoutService service =
            new TripPlanCheckoutService(tripPlanService, bookingService, paymentService);

    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    private TripPlanItem bookableItem(String id, String title) {
        return new TripPlanItem(id, "10:00", "EXPERIENCE", title, "desc",
                UUID.randomUUID(), "slug-" + id, title, "https://x/experience/slug-" + id,
                "https://x/experience/e/book", UUID.randomUUID(), Instant.now(), Instant.now(),
                new BigDecimal("40.00"), null, null, true, null);
    }

    private TripPlanItem suggestionItem(String id) {
        return new TripPlanItem(id, "09:00", "FOOD", "Breakfast", "desc",
                null, null, null, null, null, null, null, null, null,
                "Cafe", "https://maps", false, null);
    }

    private void stubPlan(TripPlanItem... items) {
        TripPlanDocument document = new TripPlanDocument("Trip", "Summary", "EUR",
                BigDecimal.ZERO, List.of(new TripPlanDay(LocalDate.now().plusDays(3), "Day", List.of(items))),
                List.of());
        when(tripPlanService.getPlanEntityByToken("tok"))
                .thenReturn(new TripPlanService.TripPlanWithDocument(planId, 2, document));
    }

    private PaymentGroupResponse groupResponse() {
        return new PaymentGroupResponse("grouptoken", PaymentGroupStatus.PROCESSING,
                new BigDecimal("80.00"), BigDecimal.ZERO, "EUR", "https://stripe/checkout",
                Instant.now(), null, List.of());
    }

    private TripPlanCheckoutRequest request(List<String> itemIds) {
        return new TripPlanCheckoutRequest(itemIds, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("a sold-out item is skipped with its reason; the rest proceed to one payment group")
    void partialFailureSkipsAndProceeds() {
        TripPlanItem ok = bookableItem("d1-i2", "Canal walk");
        TripPlanItem soldOut = bookableItem("d1-i4", "Food tour");
        stubPlan(ok, soldOut);

        UUID okBookingId = UUID.randomUUID();
        BookingResponse okBooking = mock(BookingResponse.class);
        when(okBooking.id()).thenReturn(okBookingId);

        when(bookingService.createBooking(eq(userId), any(CreateBookingRequest.class)))
                .thenAnswer(inv -> {
                    CreateBookingRequest req = inv.getArgument(1);
                    if (req.availabilitySlotId().equals(soldOut.slotId())) {
                        throw new BadRequestException("Not enough capacity remaining for this slot");
                    }
                    return okBooking;
                });
        when(paymentService.createGroupCheckout(eq(userId), any(), any(), any(), eq(planId)))
                .thenReturn(groupResponse());

        TripPlanCheckoutResponse response =
                service.checkoutAsUser(userId, "tok", request(List.of("d1-i2", "d1-i4")));

        assertEquals(1, response.skippedItems().size());
        assertEquals("d1-i4", response.skippedItems().get(0).itemId());
        assertEquals("Not enough capacity remaining for this slot", response.skippedItems().get(0).reason());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> bookingIds = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(paymentService).createGroupCheckout(
                eq(userId), eq(null), bookingIds.capture(), eq(null), eq(planId));
        assertEquals(List.of(okBookingId), bookingIds.getValue(), "only the booked item is paid for");
    }

    @Test
    @DisplayName("when every selected item fails to book, the checkout fails with the reasons")
    void allFailedItemsFailTheCheckout() {
        TripPlanItem item = bookableItem("d1-i2", "Canal walk");
        stubPlan(item);
        when(bookingService.createBooking(eq(userId), any(CreateBookingRequest.class)))
                .thenThrow(new BadRequestException("Slot is full"));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.checkoutAsUser(userId, "tok", request(List.of("d1-i2"))));
        assertEquals(true, ex.getMessage().contains("Slot is full"));
    }

    @Test
    @DisplayName("selecting a non-bookable item id is rejected up front")
    void nonBookableSelectionRejected() {
        stubPlan(suggestionItem("d1-i1"), bookableItem("d1-i2", "Canal walk"));

        assertThrows(BadRequestException.class,
                () -> service.checkoutAsUser(userId, "tok", request(List.of("d1-i1"))));
    }

    @Test
    @DisplayName("with no explicit party bands, the plan's party size books as all adults")
    void defaultPartyComposition() {
        TripPlanItem item = bookableItem("d1-i2", "Canal walk");
        stubPlan(item);

        BookingResponse booking = mock(BookingResponse.class);
        when(booking.id()).thenReturn(UUID.randomUUID());
        ArgumentCaptor<CreateBookingRequest> captor = ArgumentCaptor.forClass(CreateBookingRequest.class);
        when(bookingService.createBooking(eq(userId), captor.capture())).thenReturn(booking);
        when(paymentService.createGroupCheckout(any(), any(), any(), any(), any()))
                .thenReturn(groupResponse());

        service.checkoutAsUser(userId, "tok", request(List.of("d1-i2")));

        assertEquals(2, captor.getValue().guestsCount(), "plan party size drives guests count");
        assertEquals(2, captor.getValue().adults());
    }
}
