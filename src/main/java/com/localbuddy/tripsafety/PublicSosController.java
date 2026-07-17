package com.localbuddy.tripsafety;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous-guest SOS (booking reference + email), rate-limited per IP. Safety is never
 * login-gated — a guest who booked without an account can still raise an SOS.
 */
@RestController
@RequestMapping("/api/public/sos")
@Tag(name = "Public - SOS", description = "Guest (no-login) SOS via booking reference + email")
public class PublicSosController {

    private final TripSafetyService tripSafetyService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicSosController(TripSafetyService tripSafetyService,
                               RateLimitService rateLimitService,
                               ClientIpResolver clientIpResolver) {
        this.tripSafetyService = tripSafetyService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Raise a guest SOS", description = "Records an SOS for a guest booking and alerts support + emergency contact.")
    @PostMapping
    public ResponseEntity<TripSafetyEventResponse> raiseGuestSos(
            HttpServletRequest servletRequest,
            @Valid @RequestBody PublicSosRequest request
    ) {
        // Generous per-IP guard against email flooding; a real person never exceeds it.
        rateLimitService.checkPublicApiLimit("guest-sos:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(tripSafetyService.raiseGuestSos(request));
    }
}
