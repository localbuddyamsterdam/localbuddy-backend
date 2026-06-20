package com.localbuddy.promo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/promo-codes")
@Tag(name = "Promo Codes", description = "Authenticated user endpoints for validating promo codes")
public class PromoCodeController {

    private final PromoCodeService promoCodeService;

    public PromoCodeController(PromoCodeService promoCodeService) {
        this.promoCodeService = promoCodeService;
    }

    @Operation(
            summary = "Validate a promo code",
            description = "Validates a promo code for the authenticated user. Authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promo code validation result returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping("/validate")
    public ResponseEntity<ValidatePromoCodeResponse> validatePromoCode(
            Authentication authentication,
            @Valid @RequestBody ValidatePromoCodeRequest request
    ) {
        UUID userId = authentication != null
                ? UUID.fromString(authentication.getName())
                : null;

        return ResponseEntity.ok(
                promoCodeService.validatePromoCode(userId, request)
        );
    }
}