package com.localbuddy.payment;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/payment-groups")
@Tag(name = "Public Payment Groups", description = "Status of bundle checkouts (one payment covering several bookings)")
public class PublicPaymentGroupController {

    private final PaymentService paymentService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicPaymentGroupController(
            PaymentService paymentService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.paymentService = paymentService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Bundle checkout status",
            description = "Status + member bookings of a bundle checkout, addressed by its unguessable token "
                    + "(carried on the Stripe success/cancel URLs).")
    @GetMapping("/{groupToken}")
    public ResponseEntity<PaymentGroupResponse> getPaymentGroup(
            HttpServletRequest servletRequest,
            @PathVariable String groupToken
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("payment-group-status:" + clientIp);
        return ResponseEntity.ok(paymentService.getPaymentGroupByToken(groupToken));
    }
}
