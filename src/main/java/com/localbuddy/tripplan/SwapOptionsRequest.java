package com.localbuddy.tripplan;

import jakarta.validation.constraints.Size;

/** Optional refine instruction for the swap dialog ("something cheaper", "more food"). */
public record SwapOptionsRequest(
        @Size(max = 300, message = "Keep the instruction under 300 characters")
        String instruction
) {
}
