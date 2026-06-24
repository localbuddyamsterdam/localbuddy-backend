package com.localbuddy.newsletter;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/newsletter")
@Tag(name = "Public - Newsletter", description = "Anonymous newsletter subscribe / confirm / unsubscribe (rate limited)")
public class PublicNewsletterController {

    private final NewsletterService newsletterService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicNewsletterController(NewsletterService newsletterService,
                                      RateLimitService rateLimitService,
                                      ClientIpResolver clientIpResolver) {
        this.newsletterService = newsletterService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Subscribe to the newsletter",
            description = "Anonymous subscribe. Sends a double-opt-in confirmation email; the subscription is "
                    + "PENDING until the emailed link is confirmed.")
    @PostMapping("/subscribe")
    public ResponseEntity<NewsletterSubscriptionResponse> subscribe(
            HttpServletRequest servletRequest,
            @Valid @RequestBody SubscribeNewsletterRequest request
    ) {
        rateLimitService.checkPublicApiLimit("newsletter-subscribe:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(newsletterService.subscribePublic(request));
    }

    @Operation(summary = "Confirm a subscription (double opt-in)")
    @PostMapping("/confirm")
    public ResponseEntity<NewsletterSubscriptionResponse> confirm(
            HttpServletRequest servletRequest,
            @RequestParam("token") String token
    ) {
        rateLimitService.checkPublicApiLimit("newsletter-confirm:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(newsletterService.confirm(token));
    }

    @Operation(summary = "Unsubscribe (one-click)", description = "Tokenized unsubscribe; no login required.")
    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(
            HttpServletRequest servletRequest,
            @RequestParam("token") String token
    ) {
        rateLimitService.checkPublicApiLimit("newsletter-unsubscribe:" + clientIpResolver.resolveClientIp(servletRequest));
        newsletterService.unsubscribe(token);
        return ResponseEntity.noContent().build();
    }
}
