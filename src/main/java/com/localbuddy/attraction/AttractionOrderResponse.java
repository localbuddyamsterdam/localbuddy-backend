package com.localbuddy.attraction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A recorded in-app attraction ticket order, as shown in the traveler's account. */
public record AttractionOrderResponse(
        UUID id,
        String provider,
        String productId,
        String productTitle,
        String citySlug,
        LocalDate visitDate,
        String timeslot,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        AttractionBookingStatus status,
        String ticketUrl,
        Instant createdAt
) {

    static AttractionOrderResponse from(AttractionBooking booking) {
        return new AttractionOrderResponse(
                booking.getId(),
                booking.getProvider(),
                booking.getProductId(),
                booking.getProductTitle(),
                booking.getCitySlug(),
                booking.getVisitDate(),
                booking.getTimeslot(),
                booking.getQuantity(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                booking.getStatus(),
                booking.getTicketUrl(),
                booking.getCreatedAt()
        );
    }
}
