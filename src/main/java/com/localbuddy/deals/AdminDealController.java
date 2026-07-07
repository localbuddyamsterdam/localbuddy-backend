package com.localbuddy.deals;

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
@RequestMapping("/api/admin/deals")
@Tag(name = "Admin - Deals", description = "Admin management of promotional deals (scoped to experience, category, city, or global)")
@SecurityRequirement(name = "bearerAuth")
public class AdminDealController {

    private final DealService dealService;

    public AdminDealController(DealService dealService) {
        this.dealService = dealService;
    }

    @Operation(summary = "Create a deal",
            description = "Creates a deal scoped GLOBAL/CITY/EXPERIENCE/CATEGORY. The matching target id is required for the scope. Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Deal created"),
            @ApiResponse(responseCode = "400", description = "Invalid request (missing target for scope, bad discount, or bad window)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @PostMapping
    public ResponseEntity<DealResponse> createDeal(
            @Valid @RequestBody CreateDealRequest request
    ) {
        DealResponse response = dealService.createDeal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List all deals",
            description = "Returns all deals, including inactive and expired ones. Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deals retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping
    public ResponseEntity<List<DealResponse>> listDeals() {
        return ResponseEntity.ok(dealService.listAll());
    }

    @Operation(summary = "Get a deal by id", description = "Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deal retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Deal not found")
    })
    @GetMapping("/{dealId}")
    public ResponseEntity<DealResponse> getDeal(
            @PathVariable UUID dealId
    ) {
        return ResponseEntity.ok(dealService.getById(dealId));
    }

    @Operation(summary = "Update a deal",
            description = "Replaces a deal's configuration (scope, target, discount, window, priority). Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deal updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Deal not found")
    })
    @PutMapping("/{dealId}")
    public ResponseEntity<DealResponse> updateDeal(
            @PathVariable UUID dealId,
            @Valid @RequestBody UpdateDealRequest request
    ) {
        return ResponseEntity.ok(dealService.updateDeal(dealId, request));
    }

    @Operation(summary = "Deactivate a deal",
            description = "Soft-deletes a deal so it no longer resolves as live. Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deal deactivated"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Deal not found")
    })
    @PostMapping("/{dealId}/deactivate")
    public ResponseEntity<DealResponse> deactivateDeal(
            @PathVariable UUID dealId
    ) {
        return ResponseEntity.ok(dealService.deactivate(dealId));
    }
}
