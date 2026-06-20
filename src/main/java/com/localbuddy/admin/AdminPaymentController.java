package com.localbuddy.admin;

import com.localbuddy.payment.PaymentResponse;
import com.localbuddy.payment.PaymentService;
import com.localbuddy.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/payments")
@Tag(name = "Admin - Payments", description = "Admin endpoints for viewing payment records across the platform")
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(
            summary = "List payments",
            description = "Returns all payment records, optionally filtered by status. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)")
    })
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getAdminPayments(
            @RequestParam(required = false) PaymentStatus status
    ) {
        return ResponseEntity.ok(paymentService.getAdminPayments(status));
    }

    @Operation(
            summary = "Get a payment by ID",
            description = "Returns the full details of a single payment record. Admin only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not authorized (admin only)"),
            @ApiResponse(responseCode = "404", description = "Payment not found")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getAdminPaymentById(
            @PathVariable UUID paymentId
    ) {
        return ResponseEntity.ok(paymentService.getAdminPaymentById(paymentId));
    }
}