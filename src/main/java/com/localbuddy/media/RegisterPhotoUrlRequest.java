package com.localbuddy.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Register an externally-hosted photo URL (e.g. uploaded directly to blob via SAS). */
public record RegisterPhotoUrlRequest(
        @NotBlank @Size(max = 2000) String url,
        @Size(max = 300) String caption
) {
}
