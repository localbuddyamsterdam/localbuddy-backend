package com.localbuddy.tripplan;

import java.util.UUID;

/**
 * Optional body for the swap endpoint: the slot the traveler picked in the swap dialog.
 * Null (or no body at all — older clients) falls back to the closest-time auto-pick.
 */
public record SwapItemRequest(UUID slotId) {
}
