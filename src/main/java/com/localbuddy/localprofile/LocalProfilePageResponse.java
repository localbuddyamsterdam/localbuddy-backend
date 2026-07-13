package com.localbuddy.localprofile;

import java.util.List;

/**
 * A page of approved local (host) profiles for public browsing.
 * {@code page} is zero-based. Carries the PII-free {@link PublicLocalProfileResponse}
 * projection — this is served to anonymous callers.
 */
public record LocalProfilePageResponse(
        List<PublicLocalProfileResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
}
