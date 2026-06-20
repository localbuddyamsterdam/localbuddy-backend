package com.localbuddy.media;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** Desired display order; photos are sorted to match the order of this list. */
public record ReorderPhotosRequest(
        @NotEmpty List<UUID> photoIds
) {
}
