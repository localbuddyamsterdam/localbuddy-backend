package com.localbuddy.experience;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateExperienceCategoryRequest(

        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name cannot exceed 100 characters")
        String name,

        @Size(max = 2000, message = "Description cannot exceed 2000 characters")
        String description,

        Integer displayOrder
) {
}
