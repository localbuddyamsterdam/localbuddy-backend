package com.localbuddy.giftcard;

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
@RequestMapping("/api/public/gift-cards")
@Tag(name = "Public Gift Cards", description = "Public gift card balance lookup")
public class PublicGiftCardController {

    private final GiftCardService giftCardService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicGiftCardController(GiftCardService giftCardService,
                                    RateLimitService rateLimitService,
                                    ClientIpResolver clientIpResolver) {
        this.giftCardService = giftCardService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Check a gift card balance by code")
    @GetMapping("/{code}/balance")
    public ResponseEntity<GiftCardBalanceResponse> checkBalance(
            HttpServletRequest servletRequest,
            @PathVariable String code
    ) {
        // Rate-limit per client IP so the gift-card code space can't be brute-force enumerated.
        rateLimitService.checkPublicApiLimit(
                "gift-card-balance:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(giftCardService.checkBalance(code));
    }
}
