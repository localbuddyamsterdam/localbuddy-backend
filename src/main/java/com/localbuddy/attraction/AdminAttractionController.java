package com.localbuddy.attraction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin probe for the attractions integration — same role as the WhatsApp template-test
 * endpoint: verify credentials and city mapping end-to-end right after setup, without
 * touching the public surface.
 */
@RestController
@RequestMapping("/api/admin/attractions")
@Tag(name = "Admin - Attractions", description = "Verify the ticket-provider integration")
@SecurityRequirement(name = "bearerAuth")
public class AdminAttractionController {

    private final AttractionService attractionService;

    public AdminAttractionController(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    @Operation(summary = "Probe the ticket provider",
            description = "Calls the provider's product search for a city and returns the count plus a small "
                    + "sample, proving the API key and city mapping work.")
    @GetMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestParam String citySlug) {
        AttractionStatusResponse status = attractionService.status();
        if (!status.configured()) {
            return ResponseEntity.ok(Map.of("configured", false, "bookingEnabled", false));
        }
        List<AttractionProduct> products = attractionService.search(citySlug, null, "en");
        return ResponseEntity.ok(Map.of(
                "configured", true,
                "bookingEnabled", status.bookingEnabled(),
                "productCount", products.size(),
                "sample", products.stream().limit(3).toList()
        ));
    }
}
