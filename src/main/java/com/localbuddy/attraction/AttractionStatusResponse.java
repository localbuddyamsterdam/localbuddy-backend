package com.localbuddy.attraction;

/**
 * Feature gates for the frontend: {@code configured} shows/hides the attractions surface
 * entirely; {@code bookingEnabled} decides between in-app booking and link-out-to-provider
 * ("Get tickets") for each product.
 */
public record AttractionStatusResponse(boolean configured, boolean bookingEnabled) {
}
