package com.localbuddy.tripplan;

import com.localbuddy.booking.BookingNotificationService;
import com.localbuddy.booking.BookingResponse;
import com.localbuddy.booking.BookingService;
import com.localbuddy.booking.CreateBookingRequest;
import com.localbuddy.booking.CreateGuestBookingRequest;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.payment.PaymentGroupResponse;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.promo.PromoCodeService;
import com.localbuddy.referral.ReferralService;
import com.localbuddy.referral.ValidateReferralCodeRequest;
import com.localbuddy.referral.ValidateReferralCodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Book my whole trip": turns the multi-selected bookable items of a saved AI itinerary
 * into real bookings (one per item, via the exact same validation/locking path as single
 * bookings) and one bundle payment covering all of them.
 *
 * <p>Discount parity with the single checkout: stackable promo codes are accepted for the
 * bundle and applied per booking — each code rides only on the bookings it validates for
 * (a code scoped to one experience discounts just that item), and a code valid for nothing
 * rejects the checkout with the reason. One referral code is applied to a single booking
 * (the priciest). The gift card stays a group-level payment method, exactly as before.
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
    private final PromoCodeService promoCodeService;
    private final ReferralService referralService;
    private final BookingNotificationService bookingNotificationService;

    public TripPlanCheckoutService(
            TripPlanService tripPlanService,
            BookingService bookingService,
            PaymentService paymentService,
            PromoCodeService promoCodeService,
            ReferralService referralService,
            BookingNotificationService bookingNotificationService
    ) {
        this.tripPlanService = tripPlanService;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.promoCodeService = promoCodeService;
        this.referralService = referralService;
        this.bookingNotificationService = bookingNotificationService;
    }

    public TripPlanCheckoutResponse checkoutAsUser(UUID userId, String token, TripPlanCheckoutRequest request) {
        TripPlanService.TripPlanWithDocument plan = tripPlanService.getPlanEntityByToken(token);
        List<TripPlanItem> selectedItems = resolveSelectedItems(plan.document(), request.selectedItemIds());
        PartyComposition party = resolveParty(plan.partySize(),
                request.adults(), request.teens(), request.children(), request.infants());

        BundleCodes codes = resolveBundleCodes(userId, null, selectedItems, party,
                plan.privateTour(), plan.document().currency(),
                request.promoCodes(), request.referralCode());

        List<UUID> bookingIds = new ArrayList<>();
        List<TripPlanSkippedItem> skipped = new ArrayList<>();

        for (TripPlanItem item : selectedItems) {
            List<String> itemPromoCodes = codes.promoCodesFor(item.id());
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
                    itemPromoCodes.isEmpty() ? null : itemPromoCodes,
                    codes.referralCodeFor(item.id()),
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
                // notifyTraveler=false — the bundle sends one combined "complete payment" email
                // below instead of one per item; the host still gets notified per booking.
                BookingResponse booking = bookingService.createBooking(userId, bookingRequest, false);
                bookingIds.add(booking.id());
            } catch (RuntimeException ex) {
                log.info("Trip-plan item {} could not be booked: {}", item.id(), ex.getMessage());
                skipped.add(new TripPlanSkippedItem(item.id(), item.title(), safeReason(ex)));
            }
        }

        requireAnyBooked(bookingIds, skipped);

        PaymentGroupResponse paymentGroup = paymentService.createGroupCheckout(
                userId, null, bookingIds, request.giftCardCode(), plan.tripPlanId());
        bookingNotificationService.createBundleBookingCreatedNotification(userId, null, null, paymentGroup);

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

        BundleCodes codes = resolveBundleCodes(null, request.guestEmail(), selectedItems, party,
                plan.privateTour(), plan.document().currency(),
                request.promoCodes(), request.referralCode());

        List<UUID> bookingIds = new ArrayList<>();
        List<TripPlanSkippedItem> skipped = new ArrayList<>();

        for (TripPlanItem item : selectedItems) {
            List<String> itemPromoCodes = codes.promoCodesFor(item.id());
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
                    itemPromoCodes.isEmpty() ? null : itemPromoCodes,
                    codes.referralCodeFor(item.id()),
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
                // notifyTraveler=false — the bundle sends one combined "complete payment" email
                // below instead of one per item; the host still gets notified per booking.
                BookingResponse booking = bookingService.createGuestBooking(bookingRequest, clientIp, userAgent, false);
                bookingIds.add(booking.id());
            } catch (RuntimeException ex) {
                log.info("Trip-plan item {} could not be booked (guest): {}", item.id(), ex.getMessage());
                skipped.add(new TripPlanSkippedItem(item.id(), item.title(), safeReason(ex)));
            }
        }

        requireAnyBooked(bookingIds, skipped);

        PaymentGroupResponse paymentGroup = paymentService.createGroupCheckout(
                null, request.guestEmail(), bookingIds, request.giftCardCode(), plan.tripPlanId());
        bookingNotificationService.createBundleBookingCreatedNotification(
                null, request.guestEmail(), request.guestPhone(), paymentGroup);

        return new TripPlanCheckoutResponse(paymentGroup, skipped);
    }

    // ------------------------------------------------------------------
    // Discount codes across the bundle
    // ------------------------------------------------------------------

    /** Which promo codes ride on which item, and which single item carries the referral. */
    private record BundleCodes(Map<String, List<String>> promoByItemId, String referralItemId, String referralCode) {

        static final BundleCodes NONE = new BundleCodes(Map.of(), null, null);

        List<String> promoCodesFor(String itemId) {
            return promoByItemId.getOrDefault(itemId, List.of());
        }

        String referralCodeFor(String itemId) {
            return itemId.equals(referralItemId) ? referralCode : null;
        }
    }

    /**
     * Validates the bundle's codes up front so an invalid code fails the checkout with a clear
     * message instead of silently skipping items. Each promo is tested per selected item
     * (scoped codes attach only where they validate); a promo valid for nothing rejects; a
     * multi-code combination that isn't combinable rejects. The referral is validated once and
     * attached to the priciest item only — it is one discount, not one per booking.
     */
    private BundleCodes resolveBundleCodes(
            UUID userId,
            String guestEmail,
            List<TripPlanItem> selectedItems,
            PartyComposition party,
            boolean privateTour,
            String currency,
            List<String> promoCodes,
            String referralCode
    ) {
        List<String> cleanCodes = promoCodes == null ? List.of() : promoCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        String cleanReferral = referralCode == null || referralCode.isBlank() ? null : referralCode.trim();
        if (cleanCodes.isEmpty() && cleanReferral == null) {
            return BundleCodes.NONE;
        }

        Map<String, List<String>> promoByItemId = new LinkedHashMap<>();
        selectedItems.forEach(item -> promoByItemId.put(item.id(), new ArrayList<>()));

        for (String code : cleanCodes) {
            boolean appliesAnywhere = false;
            for (TripPlanItem item : selectedItems) {
                if (promoApplies(userId, guestEmail, List.of(code), item, party, privateTour, currency)) {
                    promoByItemId.get(item.id()).add(code);
                    appliesAnywhere = true;
                }
            }
            if (!appliesAnywhere) {
                throw new BadRequestException(
                        "Code " + code.toUpperCase(java.util.Locale.ROOT)
                                + " can't be applied to any of the selected experiences");
            }
        }

        // Where several codes landed on the same item, the combination must also be valid
        // (non-combinable codes reject with the engine's own message).
        for (TripPlanItem item : selectedItems) {
            List<String> itemCodes = promoByItemId.get(item.id());
            if (itemCodes.size() > 1
                    && !promoApplies(userId, guestEmail, itemCodes, item, party, privateTour, currency)) {
                throw new BadRequestException("These codes can't be combined; only one can be applied");
            }
        }

        String referralItemId = null;
        if (cleanReferral != null) {
            ValidateReferralCodeResponse validation = referralService.validateReferralCode(
                    userId, new ValidateReferralCodeRequest(cleanReferral, guestEmail));
            if (!validation.valid()) {
                throw new BadRequestException(validation.message() != null
                        ? validation.message()
                        : "This referral code can't be used");
            }
            referralItemId = selectedItems.stream()
                    .max(Comparator.comparing(item -> estimatedItemBase(item, party, privateTour)))
                    .map(TripPlanItem::id)
                    .orElse(null);
        }

        return new BundleCodes(promoByItemId, referralItemId, cleanReferral);
    }

    /** True when the promo engine accepts these codes for this item (validation only, no state). */
    private boolean promoApplies(
            UUID userId, String guestEmail, List<String> codes,
            TripPlanItem item, PartyComposition party, boolean privateTour, String currency
    ) {
        try {
            promoCodeService.applyPromoCodesForBooking(
                    userId, codes, guestEmail,
                    estimatedItemBase(item, party, privateTour),
                    currency, item.experienceId());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * The item's pre-fee subtotal on the same basis BookingService will charge it: adults and
     * teens pay full price, children half, infants free; private plans carry the flat
     * whole-group price. Used only to validate codes (min-spend etc.) before booking.
     */
    private BigDecimal estimatedItemBase(TripPlanItem item, PartyComposition party, boolean privateTour) {
        BigDecimal price = item.pricePerGuest() == null ? BigDecimal.ZERO : item.pricePerGuest();
        if (privateTour) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        int fullPayers = (party.adults() != null ? party.adults() : 0)
                + (party.teens() != null ? party.teens() : 0);
        int kids = party.children() != null ? party.children() : 0;
        if (fullPayers + kids == 0) {
            return price.multiply(BigDecimal.valueOf(party.guestsCount())).setScale(2, RoundingMode.HALF_UP);
        }
        return price.multiply(BigDecimal.valueOf(fullPayers))
                .add(price.multiply(new BigDecimal("0.5")).multiply(BigDecimal.valueOf(kids)))
                .setScale(2, RoundingMode.HALF_UP);
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
