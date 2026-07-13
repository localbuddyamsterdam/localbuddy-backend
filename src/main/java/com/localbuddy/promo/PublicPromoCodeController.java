package com.localbuddy.promo;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/promo-codes")
@Tag(name = "Public - Promo Codes", description = "Public endpoints for validating promo codes (no authentication required)")
public class PublicPromoCodeController {

    private final PromoCodeService promoCodeService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicPromoCodeController(PromoCodeService promoCodeService,
                                     RateLimitService rateLimitService,
                                     ClientIpResolver clientIpResolver) {
        this.promoCodeService = promoCodeService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Validate a promo code (guest)",
            description = "Validates a promo code for a guest user. No authentication required. Rate-limited per client IP."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promo code validation result returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "429", description = "Too many requests (rate limit exceeded)")
    })
    @PostMapping("/validate")
    public ResponseEntity<ValidatePromoCodeResponse> validateGuestPromoCode(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ValidatePromoCodeRequest request
    ) {
        // Rate-limit per client IP so valid promo codes can't be brute-force enumerated.
        rateLimitService.checkPublicApiLimit(
                "promo-validate:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(
                promoCodeService.validatePromoCode(null, request)
        );
    }
}
