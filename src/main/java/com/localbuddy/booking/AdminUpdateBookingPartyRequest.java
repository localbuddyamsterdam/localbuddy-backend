package com.localbuddy.booking;

import jakarta.validation.constraints.Min;

/**
 * Admin adjustment of a booking's party composition (e.g. removing a guest).
 * Only reductions are allowed; adding guests must go through a new booking so
 * payment is collected. Null bands are treated as zero.
 */
public record AdminUpdateBookingPartyRequest(
        @Min(value = 0, message = "Adults cannot be negative") Integer adults,
        @Min(value = 0, message = "Teens cannot be negative") Integer teens,
        @Min(value = 0, message = "Children cannot be negative") Integer children,
        @Min(value = 0, message = "Infants cannot be negative") Integer infants
) {
}
