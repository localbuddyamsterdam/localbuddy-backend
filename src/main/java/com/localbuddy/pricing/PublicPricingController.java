package com.localbuddy.pricing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, unauthenticated pricing facts (permitAll via {@code /api/public/**}).
 * Lets the storefront render the live service fee — checkout preview, booking
 * terms and help copy — instead of hardcoding a percentage that drifts from
 * what admins configure in the rate tables.
 */
@RestController
@RequestMapping("/api/public/pricing")
@Tag(name = "Public - Pricing", description = "Customer-facing pricing configuration")
public class PublicPricingController {

    private final PublicPricingService publicPricingService;

    public PublicPricingController(PublicPricingService publicPricingService) {
        this.publicPricingService = publicPricingService;
    }

    @Operation(summary = "Platform-default service fee",
            description = "The platform-wide customer service fee in percent, plus the VAT percent applied on the fee. "
                    + "Some cities and experiences may have a lower (even 0%) override — use the per-experience endpoint "
                    + "for the exact rate at checkout.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service-fee configuration returned")
    })
    @GetMapping("/service-fee")
    public ResponseEntity<PublicServiceFeeResponse> platformServiceFee() {
        return ResponseEntity.ok(publicPricingService.platformServiceFee());
    }

    @Operation(summary = "Effective service fee for an experience",
            description = "The service fee in percent that will actually be charged for the given approved experience, "
                    + "honouring EXPERIENCE/HOST/CATEGORY/CITY overrides (which may be 0%).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Effective service fee returned"),
            @ApiResponse(responseCode = "404", description = "Approved experience not found")
    })
    @GetMapping("/service-fee/for-experience/{experienceId}")
    public ResponseEntity<PublicServiceFeeResponse> serviceFeeForExperience(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(publicPricingService.serviceFeeForExperience(experienceId));
    }
}
