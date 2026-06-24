package com.localbuddy.newsletter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/newsletter")
@Tag(name = "Admin - Newsletter", description = "Admin newsletter subscriber list + broadcast")
@SecurityRequirement(name = "bearerAuth")
public class AdminNewsletterController {

    private final NewsletterService newsletterService;

    public AdminNewsletterController(NewsletterService newsletterService) {
        this.newsletterService = newsletterService;
    }

    @Operation(summary = "List newsletter subscriptions")
    @GetMapping("/subscriptions")
    public ResponseEntity<List<NewsletterSubscriptionResponse>> list() {
        return ResponseEntity.ok(newsletterService.listAll());
    }

    @Operation(summary = "Send a newsletter broadcast",
            description = "Sends to all CONFIRMED subscribers in the target audience (ALL also includes "
                    + "traveler + host segments). Returns the number of recipients queued.")
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Integer>> broadcast(@Valid @RequestBody NewsletterBroadcastRequest request) {
        int recipients = newsletterService.broadcast(request);
        return ResponseEntity.ok(Map.of("recipients", recipients));
    }
}
