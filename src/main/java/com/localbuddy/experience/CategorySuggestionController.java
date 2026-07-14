package com.localbuddy.experience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/category-suggestions")
@Tag(name = "Category Suggestions", description = "Hosts propose new experience categories for admin review")
@SecurityRequirement(name = "bearerAuth")
public class CategorySuggestionController {

    private final CategorySuggestionService categorySuggestionService;

    public CategorySuggestionController(CategorySuggestionService categorySuggestionService) {
        this.categorySuggestionService = categorySuggestionService;
    }

    @Operation(
            summary = "Suggest a new category",
            description = "Authenticated hosts propose a category from the listing form. It enters a review queue; "
                    + "an admin decides whether to add it. This never creates a category on its own."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Suggestion submitted"),
            @ApiResponse(responseCode = "400", description = "Invalid name, already exists, or already suggested"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<CategorySuggestionResponse> suggest(
            Authentication authentication,
            @Valid @RequestBody CreateCategorySuggestionRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categorySuggestionService.submit(userId, request));
    }
}
