package com.localbuddy.promo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/promo-codes")
@Tag(name = "Admin - Promo Codes", description = "Admin-only endpoints for creating and managing promo codes")
public class AdminPromoCodeController {

    private final PromoCodeService promoCodeService;

    public AdminPromoCodeController(PromoCodeService promoCodeService) {
        this.promoCodeService = promoCodeService;
    }

    @Operation(
            summary = "Create a promo code",
            description = "Creates a new promo code. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Promo code created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    })
    @PostMapping
    public ResponseEntity<PromoCodeResponse> createPromoCode(
            @Valid @RequestBody CreatePromoCodeRequest request
    ) {
        PromoCodeResponse response = promoCodeService.createPromoCode(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "List promo codes",
            description = "Returns all promo codes. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promo codes retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    })
    @GetMapping
    public ResponseEntity<List<PromoCodeResponse>> listPromoCodes() {
        return ResponseEntity.ok(promoCodeService.listPromoCodes());
    }

    @Operation(
            summary = "Get a promo code by ID",
            description = "Retrieves a single promo code by its identifier. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promo code retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Caller is not an administrator"),
            @ApiResponse(responseCode = "404", description = "Promo code not found")
    })
    @GetMapping("/{promoCodeId}")
    public ResponseEntity<PromoCodeResponse> getPromoCode(
            @Parameter(description = "Identifier of the promo code to retrieve") @PathVariable UUID promoCodeId
    ) {
        return ResponseEntity.ok(promoCodeService.getPromoCode(promoCodeId));
    }
}