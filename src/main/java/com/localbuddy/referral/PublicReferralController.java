package com.localbuddy.referral;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/referrals")
@Tag(name = "Public Referrals", description = "Public endpoints for validating referral codes without authentication")
public class PublicReferralController {

    private final ReferralService referralService;

    public PublicReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    @Operation(
            summary = "Validate a referral code as a guest",
            description = "Validates a referral code for an unauthenticated (guest) user. Public; no authentication required."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Referral code validation result returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping("/validate")
    public ResponseEntity<ValidateReferralCodeResponse> validateGuestReferralCode(
            @Valid @RequestBody ValidateReferralCodeRequest request
    ) {
        return ResponseEntity.ok(referralService.validateReferralCode(null, request));
    }
}