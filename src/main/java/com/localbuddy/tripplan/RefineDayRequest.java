package com.localbuddy.tripplan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Free-text instruction for refining one itinerary day ("more food, slower morning"). */
public record RefineDayRequest(
        @NotBlank(message = "Tell the Genie what to change about this day")
        @Size(max = 300, message = "Keep the instruction under 300 characters")
        String instruction
) {
}
