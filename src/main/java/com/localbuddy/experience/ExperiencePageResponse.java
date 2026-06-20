package com.localbuddy.experience;

import java.util.List;

/** A page of experience search results plus paging metadata. */
public record ExperiencePageResponse(
        List<ExperienceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
