package com.localbuddy.localprofile;

import com.localbuddy.experience.ExperienceCategoryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public projection of a local (host) profile for unauthenticated browsing.
 * <p>
 * Deliberately a trimmed subset of {@link LocalProfileResponse}: it omits all
 * host PII (phone number, current address, date of birth, bank details,
 * VAT/tax/business-registration identifiers) and internal review/verification
 * fields (approval status, admin notes, rejection/changes-requested reasons,
 * commission rate) that must never reach anonymous callers.
 * <p>
 * The four name fields (display / preferred / legal-first / legal-last) are
 * retained because the public {@code /locals} list searches hosts by name
 * (frontend {@code locals-list.component.ts} / {@code mapHost}).
 */
public record PublicLocalProfileResponse(
        UUID id,
        UUID userId,

        String displayName,
        String preferredName,
        String legalFirstName,
        String legalLastName,

        String profilePhotoUrl,
        String hostCity,
        String country,
        String bio,

        List<String> experienceLanguages,
        List<ExperienceCategoryResponse> experienceCategories,

        Gender gender,
        LocalVerificationStatus verificationStatus,

        BigDecimal ratingAvg,
        Integer totalReviews,

        Instant createdAt
) {
}
