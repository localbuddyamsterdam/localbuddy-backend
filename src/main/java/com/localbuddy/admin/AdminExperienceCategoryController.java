package com.localbuddy.admin;

import com.localbuddy.experience.CreateExperienceCategoryRequest;
import com.localbuddy.experience.ExperienceCategoryResponse;
import com.localbuddy.experience.ExperienceCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/experience-categories")
@Tag(name = "Admin - Experience Categories", description = "Admin endpoints for managing the categories an experience can belong to")
@SecurityRequirement(name = "bearerAuth")
public class AdminExperienceCategoryController {

    private final ExperienceCategoryService experienceCategoryService;

    public AdminExperienceCategoryController(ExperienceCategoryService experienceCategoryService) {
        this.experienceCategoryService = experienceCategoryService;
    }

    @Operation(
            summary = "List all experience categories",
            description = "Returns all categories, including inactive ones. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Categories retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping
    public ResponseEntity<List<ExperienceCategoryResponse>> getAllCategories() {
        return ResponseEntity.ok(experienceCategoryService.getAllCategories());
    }

    @Operation(
            summary = "Add an experience category",
            description = "Creates a new category that hosts can assign to experiences. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Category created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request or duplicate category name"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @PostMapping
    public ResponseEntity<ExperienceCategoryResponse> createCategory(
            @Valid @RequestBody CreateExperienceCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(experienceCategoryService.createCategory(request));
    }

    @Operation(
            summary = "Activate an experience category",
            description = "Makes a category selectable for experiences. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category activated successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @PostMapping("/{categoryId}/activate")
    public ResponseEntity<ExperienceCategoryResponse> activateCategory(@PathVariable UUID categoryId) {
        return ResponseEntity.ok(experienceCategoryService.setCategoryActive(categoryId, true));
    }

    @Operation(
            summary = "Deactivate an experience category",
            description = "Removes a category from the selectable pool. Existing experiences are unaffected. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Category deactivated successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Category not found")
    })
    @PostMapping("/{categoryId}/deactivate")
    public ResponseEntity<ExperienceCategoryResponse> deactivateCategory(@PathVariable UUID categoryId) {
        return ResponseEntity.ok(experienceCategoryService.setCategoryActive(categoryId, false));
    }
}
