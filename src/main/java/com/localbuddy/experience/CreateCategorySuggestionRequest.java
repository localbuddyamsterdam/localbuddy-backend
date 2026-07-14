package com.localbuddy.experience;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A host's proposal for a new experience category. */
public record CreateCategorySuggestionRequest(

        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name cannot exceed 100 characters")
        String name,

        @Size(max = 500, message = "Note cannot exceed 500 characters")
        String note
) {
}
