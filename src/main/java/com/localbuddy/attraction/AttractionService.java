package com.localbuddy.attraction;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.City;
import com.localbuddy.experience.CityRepository;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Third-party attraction tickets (museums, landmarks) via the Tiqets aggregator — the
 * "one-stop trip" building block next to LocalBuddy's own experiences.
 *
 * <p>Two independent toggles (both config/env, following the platform convention that a blank
 * env var means off):
 * <ul>
 *   <li>{@code app.attractions.enabled} + {@code TIQETS_API_KEY} — the feature exists at all
 *       (search + affiliate "Get tickets" link-outs).</li>
 *   <li>{@code app.attractions.booking-enabled} — in-app ordering through the distributor API
 *       (requires an approved Tiqets distributor account). Off = link-out only.</li>
 * </ul>
 */
@Service
public class AttractionService {

    private static final Logger log = LoggerFactory.getLogger(AttractionService.class);
    private static final int MAX_AVAILABILITY_DAYS = 60;

    private final TiqetsClient tiqetsClient;
    private final CityRepository cityRepository;
    private final UserRepository userRepository;
    private final AttractionBookingRepository attractionBookingRepository;
    private final boolean enabled;
    private final boolean bookingEnabled;

    public AttractionService(
            TiqetsClient tiqetsClient,
            CityRepository cityRepository,
            UserRepository userRepository,
            AttractionBookingRepository attractionBookingRepository,
            @Value("${app.attractions.enabled:true}") boolean enabled,
            @Value("${app.attractions.booking-enabled:false}") boolean bookingEnabled) {
        this.tiqetsClient = tiqetsClient;
        this.cityRepository = cityRepository;
        this.userRepository = userRepository;
        this.attractionBookingRepository = attractionBookingRepository;
        this.enabled = enabled;
        this.bookingEnabled = bookingEnabled;
    }

    public AttractionStatusResponse status() {
        boolean configured = enabled && tiqetsClient.isConfigured();
        return new AttractionStatusResponse(configured, configured && bookingEnabled);
    }

    /** Attraction tickets in one of our active cities (optionally filtered by a search term). */
    public List<AttractionProduct> search(String citySlug, String query, String language) {
        requireConfigured();
        City city = cityRepository.findBySlug(citySlug == null ? "" : citySlug.trim().toLowerCase(Locale.ROOT))
                .filter(City::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));
        return tiqetsClient.searchProducts(city.getSlug(), city.getName(), query, language);
    }

    public List<AttractionAvailability> availability(String productId, LocalDate from, LocalDate to) {
        requireConfigured();
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException("A valid date range is required");
        }
        if (from.plusDays(MAX_AVAILABILITY_DAYS).isBefore(to)) {
            throw new BadRequestException("Availability can be checked at most "
                    + MAX_AVAILABILITY_DAYS + " days at a time");
        }
        return tiqetsClient.getAvailability(productId, from, to);
    }

    /**
     * Places an in-app ticket order for the logged-in traveler. The local record is written
     * first and kept on failure (status FAILED + reason) so support can always trace what the
     * provider was asked — which is why this method is deliberately NOT one transaction.
     */
    public AttractionOrderResponse createOrder(UUID userId, CreateAttractionOrderRequest request) {
        requireConfigured();
        if (!status().bookingEnabled()) {
            throw new BadRequestException(
                    "In-app attraction booking is not enabled — use the provider ticket link instead");
        }
        if (request.visitDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Visit date cannot be in the past");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        AttractionBooking booking = new AttractionBooking();
        booking.setUser(user);
        booking.setProductId(request.productId());
        booking.setProductTitle(request.productTitle());
        booking.setCitySlug(request.citySlug());
        booking.setVisitDate(request.visitDate());
        booking.setTimeslot(request.timeslotId());
        booking.setQuantity(request.quantity());
        booking.setStatus(AttractionBookingStatus.PENDING);
        booking = attractionBookingRepository.save(booking);

        try {
            TiqetsClient.TiqetsOrderResult result = tiqetsClient.createOrder(
                    request.productId(),
                    request.visitDate(),
                    request.timeslotId(),
                    request.quantity(),
                    customerName(user),
                    user.getEmail(),
                    user.getPhone());
            booking.setProviderOrderId(result.orderId());
            booking.setTicketUrl(result.ticketUrl());
            booking.setStatus(AttractionBookingStatus.CONFIRMED);
            booking = attractionBookingRepository.save(booking);
            log.info("Attraction order confirmed: booking={} provider-order={}", booking.getId(), result.orderId());
            return AttractionOrderResponse.from(booking);
        } catch (RuntimeException ex) {
            booking.setStatus(AttractionBookingStatus.FAILED);
            booking.setErrorMessage(truncate(ex.getMessage()));
            attractionBookingRepository.save(booking);
            throw ex;
        }
    }

    /** The traveler's in-app attraction orders, newest first. */
    @Transactional(readOnly = true)
    public Page<AttractionOrderResponse> listMine(UUID userId, Pageable pageable) {
        return attractionBookingRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(AttractionOrderResponse::from);
    }

    private void requireConfigured() {
        if (!status().configured()) {
            throw new BadRequestException("Attraction tickets are not configured");
        }
    }

    private static String customerName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getEmail() : name;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
