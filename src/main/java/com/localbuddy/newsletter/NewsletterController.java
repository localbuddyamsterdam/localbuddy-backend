package com.localbuddy.newsletter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/newsletter")
@Tag(name = "Newsletter", description = "Logged-in newsletter subscription")
@SecurityRequirement(name = "bearerAuth")
public class NewsletterController {

    private final NewsletterService newsletterService;

    public NewsletterController(NewsletterService newsletterService) {
        this.newsletterService = newsletterService;
    }

    @Operation(summary = "Subscribe me to the newsletter",
            description = "Subscribes the current user's email, segmented by role. Confirmation is skipped if "
                    + "the user's email is already verified.")
    @PostMapping("/subscribe")
    public ResponseEntity<NewsletterSubscriptionResponse> subscribe(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(newsletterService.subscribeAsUser(userId));
    }

    @Operation(summary = "My newsletter status")
    @GetMapping("/me")
    public ResponseEntity<NewsletterSubscriptionResponse> myStatus(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return newsletterService.getMyStatus(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
