package com.localbuddy.localprofile;

import java.util.List;

/**
 * A page of approved local (host) profiles for public browsing.
 * {@code page} is zero-based.
 */
public record LocalProfilePageResponse(
        List<LocalProfileResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
}
