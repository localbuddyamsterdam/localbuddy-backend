package com.localbuddy.wishlist;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Adds several experiences to the wishlist at once — used to merge a guest's locally-saved
 * favourites after they are forced to log in. Idempotent; unknown / non-approved / already-saved
 * ids are skipped silently.
 */
public record BatchWishlistRequest(

        @NotEmpty(message = "At least one experience id is required")
        @Size(max = 100, message = "Cannot add more than 100 experiences at once")
        List<UUID> experienceIds
) {
}
