package com.localbuddy.experience;

/**
 * Whether — and where — the host also offers this experience outside LocalBuddy.
 * Asked once when the experience is created.
 *
 * <ul>
 *   <li>{@link #NONE} — not listed anywhere else. Private bookings allowed.</li>
 *   <li>{@link #OWN_WEBSITE_SOCIAL} — host's own website / social media only. Private bookings still allowed.</li>
 *   <li>{@link #AGGREGATOR_PLATFORM} — listed on a third-party aggregator (Airbnb, Viator, GetYourGuide, …).
 *       Private (whole-slot buyout) bookings are NOT allowed, because slot exclusivity cannot be guaranteed.</li>
 * </ul>
 */
public enum ExternalListingType {
    NONE,
    OWN_WEBSITE_SOCIAL,
    AGGREGATOR_PLATFORM;

    /** Only aggregator listings block private booking. */
    public boolean blocksPrivateBooking() {
        return this == AGGREGATOR_PLATFORM;
    }
}
