package com.localbuddy.experience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/experiences")
@Tag(name = "Public Experiences", description = "Public, unauthenticated browsing and search of approved experiences")
public class PublicExperienceController {

    private final ExperienceService experienceService;

    public PublicExperienceController(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @Operation(
            summary = "List approved experiences",
            description = "Public endpoint. Returns approved experiences, optionally filtered by city, category, "
                    + "and booking mode (shared vs private)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Approved experiences retrieved successfully")
    })
    @GetMapping
    public ResponseEntity<List<ExperienceResponse>> getApprovedExperiences(
            @RequestParam(required = false) String citySlug,
            @RequestParam(required = false) String categorySlug,
            @Parameter(description = "Filter by booking mode: SHARED, PRIVATE_ALLOWED, or PRIVATE_ONLY. Omit for all.")
            @RequestParam(required = false) BookingMode bookingMode,
            @Parameter(description = "Convenience filter: true = shared only (SHARED); false = private only "
                    + "(PRIVATE_ALLOWED or PRIVATE_ONLY). Ignored when bookingMode is provided.")
            @RequestParam(required = false) Boolean shared
    ) {
        return ResponseEntity.ok(
                experienceService.getApprovedExperiences(citySlug, categorySlug, bookingMode, shared));
    }

    @Operation(
            summary = "Map markers for approved experiences",
            description = "Lightweight markers (only experiences with coordinates) for plotting on a map. "
                    + "Optionally pass the viewer's lat/lng (e.g. from the browser Geolocation API) to get a "
                    + "distanceKm per marker and a nearest-first ordering, and radiusKm to restrict to nearby ones."
    )
    @GetMapping("/map")
    public ResponseEntity<List<ExperienceMapMarker>> getMapMarkers(
            @RequestParam(required = false) String citySlug,
            @RequestParam(required = false) String categorySlug,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm
    ) {
        return ResponseEntity.ok(experienceService.getMapMarkers(citySlug, categorySlug, lat, lng, radiusKm));
    }

    @Operation(
            summary = "Search approved experiences",
            description = "Public endpoint. Searches approved experiences with optional filters (city, category, date, party "
                    + "composition) and returns a paginated result."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid search parameters")
    })
    @GetMapping("/search")
    public ResponseEntity<ExperiencePageResponse> searchApprovedExperiences(
            @RequestParam(required = false) String citySlug,
            @RequestParam(required = false) String categorySlug,
            @Parameter(description = "Desired experience date (ISO-8601, yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Number of adults in the party")
            @RequestParam(required = false) Integer adults,
            @Parameter(description = "Number of teens in the party")
            @RequestParam(required = false) Integer teens,
            @Parameter(description = "Number of children in the party")
            @RequestParam(required = false) Integer children,
            @Parameter(description = "Number of infants in the party")
            @RequestParam(required = false) Integer infants,
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(experienceService.searchApprovedExperiences(
                citySlug, categorySlug, date, adults, teens, children, infants, page, size));
    }

    @Operation(
            summary = "Advanced search of approved experiences",
            description = "Public endpoint. Extends search with price range, minimum host rating, maximum duration, and a "
                    + "keyword matched against title/description, in addition to city/category/date/party filters."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid search parameters")
    })
    @GetMapping("/search/advanced")
    public ResponseEntity<ExperiencePageResponse> advancedSearch(
            @RequestParam(required = false) String citySlug,
            @RequestParam(required = false) String categorySlug,
            @Parameter(description = "Desired experience date (ISO-8601, yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer adults,
            @RequestParam(required = false) Integer teens,
            @RequestParam(required = false) Integer children,
            @RequestParam(required = false) Integer infants,
            @Parameter(description = "Minimum price per guest")
            @RequestParam(required = false) java.math.BigDecimal minPrice,
            @Parameter(description = "Maximum price per guest")
            @RequestParam(required = false) java.math.BigDecimal maxPrice,
            @Parameter(description = "Maximum duration in minutes")
            @RequestParam(required = false) Integer maxDurationMinutes,
            @Parameter(description = "Minimum host rating (0-5)")
            @RequestParam(required = false) java.math.BigDecimal minHostRating,
            @Parameter(description = "Free-text keyword matched against title and description")
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(experienceService.advancedSearch(
                citySlug, categorySlug, date, adults, teens, children, infants,
                minPrice, maxPrice, maxDurationMinutes, minHostRating, keyword, page, size));
    }

    @Operation(
            summary = "Trending approved experiences",
            description = "Public endpoint. Returns approved experiences ranked by recent confirmed/completed "
                    + "bookings within the look-back window, tie-broken by host rating and recency. Falls back "
                    + "to top-rated/newest when booking data is sparse."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trending experiences retrieved successfully")
    })
    @GetMapping("/trending")
    public ResponseEntity<List<ExperienceResponse>> getTrendingExperiences(
            @Parameter(description = "Restrict to a city by slug")
            @RequestParam(required = false) String citySlug,
            @Parameter(description = "Look-back window in days (default 30, max 365)")
            @RequestParam(defaultValue = "30") int windowDays,
            @Parameter(description = "Max results (default 10, max 50)")
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(experienceService.getTrendingExperiences(citySlug, windowDays, limit));
    }

    @Operation(
            summary = "Get approved experience by ID",
            description = "Public endpoint. Returns a single approved experience by its ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Approved experience not found")
    })
    @GetMapping("/{experienceId}")
    public ResponseEntity<ExperienceResponse> getApprovedExperienceById(
            @PathVariable UUID experienceId
    ) {
        return ResponseEntity.ok(experienceService.getApprovedExperienceById(experienceId));
    }

    @Operation(
            summary = "Get approved experience by slug",
            description = "Public endpoint. Returns a single approved experience by its URL slug."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Experience retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Approved experience not found")
    })
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ExperienceResponse> getApprovedExperienceBySlug(
            @PathVariable String slug
    ) {
        return ResponseEntity.ok(experienceService.getApprovedExperienceBySlug(slug));
    }
}