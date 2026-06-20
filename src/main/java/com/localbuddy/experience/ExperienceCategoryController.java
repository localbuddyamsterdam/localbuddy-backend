package com.localbuddy.experience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/experience-categories")
@Tag(name = "Experience Categories", description = "Public list of categories an experience can belong to")
public class ExperienceCategoryController {

    private final ExperienceCategoryService experienceCategoryService;

    public ExperienceCategoryController(ExperienceCategoryService experienceCategoryService) {
        this.experienceCategoryService = experienceCategoryService;
    }

    @Operation(
            summary = "List active experience categories",
            description = "Public endpoint. Returns the active experience categories used to populate the category selector."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active categories retrieved successfully")
    })
    @GetMapping
    public ResponseEntity<List<ExperienceCategoryResponse>> getActiveCategories() {
        return ResponseEntity.ok(experienceCategoryService.getActiveCategories());
    }
}