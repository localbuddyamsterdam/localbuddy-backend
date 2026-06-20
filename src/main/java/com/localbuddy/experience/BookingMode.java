package com.localbuddy.experience;

/**
 * How an experience may be booked.
 *
 * <ul>
 *   <li>{@code SHARED} – joined by individual guests up to capacity (default).</li>
 *   <li>{@code PRIVATE_ALLOWED} – can be booked either shared or as a private
 *       whole-slot buyout (the first private booking closes the slot to others,
 *       and once any shared guest has booked, the private option is no longer
 *       offered for that slot).</li>
 *   <li>{@code PRIVATE_ONLY} – every booking reserves the whole slot at the
 *       private price; the slot is never shared.</li>
 * </ul>
 *
 * A non-shared mode requires a {@code privatePrice} on the experience.
 */
public enum BookingMode {
    SHARED,
    PRIVATE_ALLOWED,
    PRIVATE_ONLY
}
