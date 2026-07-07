package com.localbuddy.pricing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over the pricing rate tables. Use these to run time-boxed promos
 * (e.g. a "January 15% commission" rule) or assign reduced VAT to a category.
 * Gated to ROLE_ADMIN by {@code /api/admin/**} in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/rates")
@Tag(name = "Admin - Rates", description = "Commission, service-fee and VAT rate management")
@SecurityRequirement(name = "bearerAuth")
public class AdminRatesController {

    private final RateAdminService rateAdminService;

    public AdminRatesController(RateAdminService rateAdminService) {
        this.rateAdminService = rateAdminService;
    }

    private static UUID adminId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    // -------- Commission

    @Operation(summary = "List commission rules",
            description = "All commission rules across scopes (PLATFORM/CITY/CATEGORY/HOST/EXPERIENCE), including inactive. Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission rules retrieved"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping("/commission")
    public ResponseEntity<List<CommissionRuleResponse>> listCommission() {
        return ResponseEntity.ok(rateAdminService.listCommissionRules());
    }

    @Operation(summary = "Create a commission rule",
            description = "Adds a scoped, time-boxed commission rate (fraction 0.00–1.00, capped by app.platform.max-commission-rate). Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission rule created"),
            @ApiResponse(responseCode = "400", description = "Invalid rate (negative, >1, or above the max cap) or effective window"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @PostMapping("/commission")
    public ResponseEntity<CommissionRuleResponse> createCommission(Authentication authentication,
                                                                   @Valid @RequestBody CreateCommissionRuleRequest request) {
        return ResponseEntity.ok(rateAdminService.createCommissionRule(request, adminId(authentication)));
    }

    @Operation(summary = "Update a commission rule",
            description = "Edits the rate, effective window, note, or active flag of an existing commission rule in place (scope is immutable). Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission rule updated"),
            @ApiResponse(responseCode = "400", description = "Invalid rate or effective window"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Commission rule not found")
    })
    @PutMapping("/commission/{id}")
    public ResponseEntity<CommissionRuleResponse> updateCommission(Authentication authentication,
                                                                   @PathVariable UUID id,
                                                                   @Valid @RequestBody UpdateCommissionRuleRequest request) {
        return ResponseEntity.ok(rateAdminService.updateCommissionRule(id, request, adminId(authentication)));
    }

    @Operation(summary = "Deactivate a commission rule",
            description = "Soft-deletes a commission rule so it no longer applies during pricing. Admin only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Commission rule deactivated"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Commission rule not found")
    })
    @PostMapping("/commission/{id}/deactivate")
    public ResponseEntity<CommissionRuleResponse> deactivateCommission(Authentication authentication,
                                                                       @PathVariable UUID id) {
        return ResponseEntity.ok(rateAdminService.deactivateCommissionRule(id, adminId(authentication)));
    }

    // -------- Service fee

    @GetMapping("/service-fee")
    public ResponseEntity<List<ServiceFeeRuleResponse>> listServiceFee() {
        return ResponseEntity.ok(rateAdminService.listServiceFeeRules());
    }

    @PostMapping("/service-fee")
    public ResponseEntity<ServiceFeeRuleResponse> createServiceFee(Authentication authentication,
                                                                   @Valid @RequestBody CreateServiceFeeRuleRequest request) {
        return ResponseEntity.ok(rateAdminService.createServiceFeeRule(request, adminId(authentication)));
    }

    @PostMapping("/service-fee/{id}/deactivate")
    public ResponseEntity<ServiceFeeRuleResponse> deactivateServiceFee(Authentication authentication,
                                                                       @PathVariable UUID id) {
        return ResponseEntity.ok(rateAdminService.deactivateServiceFeeRule(id, adminId(authentication)));
    }

    // -------- VAT

    @GetMapping("/vat")
    public ResponseEntity<List<VatRateResponse>> listVat() {
        return ResponseEntity.ok(rateAdminService.listVatRates());
    }

    @PostMapping("/vat")
    public ResponseEntity<VatRateResponse> createVat(Authentication authentication,
                                                     @Valid @RequestBody CreateVatRateRequest request) {
        return ResponseEntity.ok(rateAdminService.createVatRate(request, adminId(authentication)));
    }

    @PostMapping("/vat/{id}/deactivate")
    public ResponseEntity<VatRateResponse> deactivateVat(Authentication authentication,
                                                         @PathVariable UUID id) {
        return ResponseEntity.ok(rateAdminService.deactivateVatRate(id, adminId(authentication)));
    }

    // -------- Audit

    @GetMapping("/audit")
    public ResponseEntity<List<RateChangeAudit>> auditTrail(@RequestParam(required = false) String rateType) {
        return ResponseEntity.ok(rateAdminService.auditTrail(rateType));
    }
}
