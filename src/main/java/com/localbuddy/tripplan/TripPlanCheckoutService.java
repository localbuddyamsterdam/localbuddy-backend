package com.localbuddy.tripplan;

import com.localbuddy.booking.BookingResponse;
import com.localbuddy.booking.BookingService;
import com.localbuddy.booking.CreateBookingRequest;
import com.localbuddy.booking.CreateGuestBookingRequest;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.payment.PaymentGroupResponse;
import com.localbuddy.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Book my whole trip": turns the multi-selected bookable items of a saved AI itinerary
 * into real bookings (one per item, via the exact same validation/locking path as single
 * bookings) and one bundle payment covering all of them.
 *
 * <p>Items that fail to book (slot sold out in the meantime, duplicate booking, …) are
 * skipped and reported instead of failing the whole bundle. If nothing could be booked the
 * request fails. If the payment-group creation itself fails, the created bookings stay
 * PENDING_PAYMENT and are cleaned up by the standard expiry sweep — the same orphan model
 * as a single checkout abandoned before payment.
 */
@Service
public class TripPlanCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(TripPlanCheckoutService.class);

    private final TripPlanService tripPlanService;
    private final BookingService bookingService;
    private final PaymentService paymentService;

    public TripPlanCheckoutService(
            TripPlanService tripPlanService,
            BookingService bookingService,
            PaymentService paymentService
    ) {
        this.tripPlanService = tripPlanService;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
    }

    public TripPlanCheckoutResponse checkoutAsUser(UUID userId, String token, TripPlanCheckoutRequest request) {
        TripPlanService.TripPlanWithDocument plan = tripPlanService.getPlanEntityByToken(token);
        List<TripPlanItem> selectedItems = resolveSelectedItems(plan.document(), request.selectedItemIds());
        PartyComposition party = resolveParty(plan.partySize(),
                request.adults(), request.teens(), request.children(), request.infants());

        List<UUID> bookingIds = new ArrayList<>();
        List<TripPlanSkippedItem> skipped = new ArrayList<>();

        for (TripPlanItem item : selectedItems) {
            CreateBookingRequest bookingRequest = new CreateBookingRequest(
                    item.experienceId(),
                    item.slotId(),
                    party.guestsCount(),
                    party.adults(),
                    party.teens(),
                    party.children(),
                    party.infants(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    // A private plan books every item as a whole-slot buyout at the flat price.
                    plan.privateTour() ? Boolean.TRUE : null,
                    request.emergencyContactFirstName(),
                    request.emergencyContactLastName(),
                    request.emergencyContactEmail(),
                    request.emergencyContactPhone(),
                    request.emergencyContactRelationship(),
                    request.whatsAppOptIn(),
                    // Bundle checkout always pays through the payment group — never waived.
                    null
            );
            try {
                BookingResponse booking = bookingService.createBooking(userId, bookingRequest);
                bookingIds.add(booking.id());
            } catch (RuntimeException ex) {
                log.info("Trip-plan item {} could not be booked: {}", item.id(), ex.getMessage());
                skipped.add(new TripPlanSkippedItem(item.id(), item.title(), safeReason(ex)));
            }
        }

        requireAnyBooked(bookingIds, skipped);

        PaymentGroupResponse paymentGroup = paymentService.createGroupCheckout(
                userId, null, bookingIds, request.giftCardCode(), plan.tripPlanId());

        return new TripPlanCheckoutResponse(paymentGroup, skipped);
    }

    public TripPlanCheckoutResponse checkoutAsGuest(
            String token,
            GuestTripPlanCheckoutRequest request,
            String clientIp,
            String userAgent
    ) {
        TripPlanService.TripPlanWithDocument plan = tripPlanService.getPlanEntityByToken(token);
        List<TripPlanItem> selectedItems = resolveSelectedItems(plan.document(), request.selectedItemIds());
        PartyComposition party = resolveParty(plan.partySize(),
                request.adults(), request.teens(), request.children(), request.infants());

        List<UUID> bookingIds = new ArrayList<>();
        List<TripPlanSkippedItem> skipped = new ArrayList<>();

        for (TripPlanItem item : selectedItems) {
            CreateGuestBookingRequest bookingRequest = new CreateGuestBookingRequest(
                    item.experienceId(),
                    item.slotId(),
                    party.guestsCount(),
                    party.adults(),
                    party.teens(),
                    party.children(),
                    party.infants(),
                    request.guestFirstName(),
                    request.guestLastName(),
                    request.guestEmail(),
                    request.guestPhone(),
                    null,
                    null,
                    null,
                    null,
                    request.acceptedTerms(),
                    request.consentVersion(),
                    // A private plan books every item as a whole-slot buyout at the flat price.
                    plan.privateTour() ? Boolean.TRUE : null,
                    request.emergencyContactFirstName(),
                    request.emergencyContactLastName(),
                    request.emergencyContactEmail(),
                    request.emergencyContactPhone(),
                    request.emergencyContactRelationship(),
                    request.whatsAppOptIn()
            );
            try {
                BookingResponse booking = bookingService.createGuestBooking(bookingRequest, clientIp, userAgent);
                bookingIds.add(booking.id());
            } catch (RuntimeException ex) {
                log.info("Trip-plan item {} could not be booked (guest): {}", item.id(), ex.getMessage());
                skipped.add(new TripPlanSkippedItem(item.id(), item.title(), safeReason(ex)));
            }
        }

        requireAnyBooked(bookingIds, skipped);

        PaymentGroupResponse paymentGroup = paymentService.createGroupCheckout(
                null, request.guestEmail(), bookingIds, request.giftCardCode(), plan.tripPlanId());

        return new TripPlanCheckoutResponse(paymentGroup, skipped);
    }

    // ------------------------------------------------------------------

    private record PartyComposition(int guestsCount, Integer adults, Integer teens, Integer children, Integer infants) {
    }

    private List<TripPlanItem> resolveSelectedItems(TripPlanDocument document, List<String> selectedItemIds) {
        Map<String, TripPlanItem> bookableById = new LinkedHashMap<>();
        document.days().forEach(day -> day.items().forEach(item -> {
            if (item.bookable() && item.slotId() != null && item.experienceId() != null) {
                bookableById.put(item.id(), item);
            }
        }));

        List<TripPlanItem> selected = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String rawId : selectedItemIds) {
            String itemId = rawId != null ? rawId.trim() : "";
            if (itemId.isEmpty() || seen.contains(itemId)) {
                continue;
            }
            seen.add(itemId);
            TripPlanItem item = bookableById.get(itemId);
            if (item == null) {
                throw new BadRequestException("Item " + itemId + " is not a bookable item of this trip plan");
            }
            selected.add(item);
        }
        if (selected.isEmpty()) {
            throw new BadRequestException("Select at least one itinerary item to book");
        }
        return selected;
    }

    private PartyComposition resolveParty(Integer planPartySize, Integer adults, Integer teens,
                                          Integer children, Integer infants) {
        boolean anyBandGiven = adults != null || teens != null || children != null || infants != null;
        if (!anyBandGiven) {
            int partySize = planPartySize != null && planPartySize > 0 ? planPartySize : 1;
            return new PartyComposition(partySize, partySize, 0, 0, 0);
        }

        int adultCount = adults != null ? adults : 0;
        int teenCount = teens != null ? teens : 0;
        int childCount = children != null ? children : 0;
        int infantCount = infants != null ? infants : 0;
        int total = adultCount + teenCount + childCount + infantCount;
        if (total < 1) {
            throw new BadRequestException("The party must include at least one guest");
        }
        if (total > 10) {
            throw new BadRequestException("The party cannot exceed 10 guests");
        }
        return new PartyComposition(total, adultCount, teenCount, childCount, infantCount);
    }

    private void requireAnyBooked(List<UUID> bookingIds, List<TripPlanSkippedItem> skipped) {
        if (bookingIds.isEmpty()) {
            String reasons = skipped.stream()
                    .map(item -> item.title() + ": " + item.reason())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("no bookable items");
            throw new BadRequestException("None of the selected items could be booked — " + reasons);
        }
    }

    private String safeReason(RuntimeException ex) {
        // Only deliberate domain messages may reach the (possibly anonymous) client; anything
        // else could leak internals (SQL state, provider errors) — log it and stay generic.
        boolean domainMessage = ex instanceof BadRequestException
                || ex instanceof com.localbuddy.common.exception.ResourceNotFoundException
                || ex instanceof com.localbuddy.common.exception.ConflictException;
        String message = ex.getMessage();
        if (!domainMessage || message == null || message.isBlank()) {
            if (!domainMessage) {
                log.warn("Non-domain booking failure hidden from client", ex);
            }
            return "This item is no longer available";
        }
        return message.length() > 200 ? message.substring(0, 199) + "…" : message;
    }
}
