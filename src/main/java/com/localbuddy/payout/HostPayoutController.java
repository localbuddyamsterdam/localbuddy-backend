package com.localbuddy.payout;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/host/payouts")
@Tag(name = "Host Payouts", description = "Authenticated host earnings, payout history, and Stripe Connect onboarding")
public class HostPayoutController {

    private final HostPayoutService hostPayoutService;

    public HostPayoutController(HostPayoutService hostPayoutService) {
        this.hostPayoutService = hostPayoutService;
    }

    @Operation(summary = "Get my earnings", description = "Returns the authenticated host's total earned, paid out, and pending balance.")
    @GetMapping("/earnings")
    public ResponseEntity<HostEarningsResponse> getMyEarnings(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(hostPayoutService.getMyEarnings(userId));
    }

    @Operation(summary = "List my payouts", description = "Returns the authenticated host's payout history.")
    @GetMapping
    public ResponseEntity<List<PayoutResponse>> getMyPayouts(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(hostPayoutService.getMyPayouts(userId));
    }

    @Operation(summary = "Start Stripe Connect onboarding",
            description = "Creates (or reuses) the host's Stripe Connect account and returns a hosted onboarding URL.")
    @PostMapping("/connect-onboarding")
    public ResponseEntity<ConnectOnboardingResponse> createConnectOnboarding(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(hostPayoutService.createConnectOnboarding(userId));
    }
}
