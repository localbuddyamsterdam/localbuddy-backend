package com.localbuddy.experience;

import jakarta.validation.constraints.Size;

/** Partial update — null fields are left unchanged (empty string clears imageUrl/description). */
public record UpdateExperienceCategoryRequest(

        @Size(max = 100, message = "Category name cannot exceed 100 characters")
        String name,

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        String description,

        @Size(max = 500, message = "Image URL cannot exceed 500 characters")
        String imageUrl,

        Integer displayOrder
) {
}
