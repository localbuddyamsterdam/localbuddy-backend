package com.localbuddy.media;

import jakarta.validation.constraints.Size;

/** Editable fields of an experience photo (currently just the caption). */
public record UpdateExperiencePhotoRequest(
        @Size(max = 300, message = "Caption cannot exceed 300 characters")
        String caption
) {
}
