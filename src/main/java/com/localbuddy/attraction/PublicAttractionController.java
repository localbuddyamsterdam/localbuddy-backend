package com.localbuddy.attraction;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Public attraction-ticket discovery (museums, landmarks — e.g. Rijksmuseum, Eiffel Tower)
 * via the Tiqets aggregator. Browsing stays public; placing an in-app order requires login
 * (see AttractionController). Every product carries an affiliate ticket link, so travelers
 * can always buy on the provider's site even when in-app booking is switched off.
 */
@RestController
@RequestMapping("/api/public/attractions")
@Tag(name = "Attractions", description = "Third-party museum & attraction tickets (aggregator-backed)")
public class PublicAttractionController {

    private final AttractionService attractionService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicAttractionController(
            AttractionService attractionService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.attractionService = attractionService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Attractions feature status",
            description = "Whether attraction tickets are configured at all, and whether in-app booking "
                    + "(vs. link-out to the provider) is enabled. The frontend gates its UI on this.")
    @GetMapping("/status")
    public ResponseEntity<AttractionStatusResponse> status() {
        return ResponseEntity.ok(attractionService.status());
    }

    @Operation(summary = "Search attraction tickets in a city",
            description = "Ticketed attractions (museums, landmarks, tours) available in one of our cities, "
                    + "normalized from the aggregator. Each result includes an affiliate ticket link.")
    @GetMapping
    public ResponseEntity<List<AttractionProduct>> search(
            HttpServletRequest servletRequest,
            @RequestParam String citySlug,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "en") String lang
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("attractions-search:" + clientIp, 20, 60);
        return ResponseEntity.ok(attractionService.search(citySlug, q, lang));
    }

    @Operation(summary = "Attraction availability",
            description = "Per-date availability (with entry timeslots where applicable) for one product.")
    @GetMapping("/{productId}/availability")
    public ResponseEntity<List<AttractionAvailability>> availability(
            HttpServletRequest servletRequest,
            @PathVariable String productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("attractions-availability:" + clientIp, 20, 60);
        return ResponseEntity.ok(attractionService.availability(productId, from, to));
    }
}
