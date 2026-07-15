package com.localbuddy.availability;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dry-run conflict check for a set of proposed session start instants of one
 * experience, against the host's whole calendar. Session length is derived from
 * the experience's duration. Powers the live "this time clashes…" banner in the editor.
 */
public record ValidateAvailabilityRequest(

        @NotNull(message = "Experience id is required")
        UUID experienceId,

        @NotEmpty(message = "At least one start time is required")
        List<@NotNull Instant> starts
) {
}
