package com.localbuddy.admin;

import com.localbuddy.experience.CategorySuggestionResponse;
import com.localbuddy.experience.CategorySuggestionService;
import com.localbuddy.experience.SuggestionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/experience-categories/suggestions")
@Tag(name = "Admin - Category Suggestions", description = "Review host-submitted category suggestions")
@SecurityRequirement(name = "bearerAuth")
public class AdminCategorySuggestionController {

    private final CategorySuggestionService categorySuggestionService;

    public AdminCategorySuggestionController(CategorySuggestionService categorySuggestionService) {
        this.categorySuggestionService = categorySuggestionService;
    }

    @Operation(
            summary = "List category suggestions",
            description = "Returns host-submitted suggestions, newest first. Optional ?status=PENDING|APPROVED|DISMISSED filter. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suggestions retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping
    public ResponseEntity<List<CategorySuggestionResponse>> list(
            @RequestParam(value = "status", required = false) SuggestionStatus status) {
        return ResponseEntity.ok(categorySuggestionService.list(status));
    }

    @Operation(
            summary = "Dismiss a suggestion",
            description = "Marks a suggestion as reviewed and not added. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suggestion dismissed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Suggestion not found")
    })
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<CategorySuggestionResponse> dismiss(@PathVariable UUID id) {
        return ResponseEntity.ok(categorySuggestionService.dismiss(id));
    }

    @Operation(
            summary = "Resolve a suggestion",
            description = "Marks a suggestion approved and links it to the category the admin created from it "
                    + "(pass resultingCategoryId). Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suggestion resolved"),
            @ApiResponse(responseCode = "400", description = "Linked category does not exist"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Suggestion not found")
    })
    @PostMapping("/{id}/resolve")
    public ResponseEntity<CategorySuggestionResponse> resolve(
            @PathVariable UUID id,
            @RequestBody(required = false) ResolveSuggestionRequest body) {
        UUID categoryId = body == null ? null : body.resultingCategoryId();
        return ResponseEntity.ok(categorySuggestionService.resolve(id, categoryId));
    }

    /** Body for {@link #resolve}: the category an admin created from the suggestion (optional). */
    public record ResolveSuggestionRequest(UUID resultingCategoryId) {
    }
}
