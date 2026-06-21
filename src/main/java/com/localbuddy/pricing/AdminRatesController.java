package com.localbuddy.pricing;

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

    @GetMapping("/commission")
    public ResponseEntity<List<CommissionRuleResponse>> listCommission() {
        return ResponseEntity.ok(rateAdminService.listCommissionRules());
    }

    @PostMapping("/commission")
    public ResponseEntity<CommissionRuleResponse> createCommission(Authentication authentication,
                                                                   @Valid @RequestBody CreateCommissionRuleRequest request) {
        return ResponseEntity.ok(rateAdminService.createCommissionRule(request, adminId(authentication)));
    }

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
