package com.localbuddy.payout;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/payouts")
@Tag(name = "Admin - Payouts", description = "Admin endpoints to view and disburse host payouts")
public class AdminPayoutController {

    private final HostPayoutService hostPayoutService;

    public AdminPayoutController(HostPayoutService hostPayoutService) {
        this.hostPayoutService = hostPayoutService;
    }

    @Operation(summary = "List all payouts", description = "Returns all payouts across hosts. Admin only.")
    @GetMapping
    public ResponseEntity<List<PayoutResponse>> getAllPayouts() {
        return ResponseEntity.ok(hostPayoutService.getAllPayouts());
    }

    @Operation(summary = "Create a payout for a host",
            description = "Aggregates the host's not-yet-paid-out earnings into a payout and (if Stripe Connect is set up) transfers it. Admin only.")
    @PostMapping("/hosts/{localProfileId}")
    public ResponseEntity<PayoutResponse> createPayoutForHost(@PathVariable UUID localProfileId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(hostPayoutService.createPayoutForHost(localProfileId));
    }

    @Operation(summary = "Mark a payout paid",
            description = "Marks a pending payout as paid (for manual/offline disbursement). Admin only.")
    @PostMapping("/{payoutId}/mark-paid")
    public ResponseEntity<PayoutResponse> markPayoutPaid(
            @PathVariable UUID payoutId,
            @RequestParam(required = false) String notes
    ) {
        return ResponseEntity.ok(hostPayoutService.markPayoutPaid(payoutId, notes));
    }
}
