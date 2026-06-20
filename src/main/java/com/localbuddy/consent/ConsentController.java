package com.localbuddy.consent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/consents")
@Tag(name = "Consents", description = "Authenticated user endpoints for viewing and accepting consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @Operation(
            summary = "Get my consent status",
            description = "Returns the current consent status for the authenticated user. Authenticated user only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent status retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/my-status")
    public ResponseEntity<ConsentStatusResponse> getMyConsentStatus(
            Authentication authentication
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(consentService.getMyConsentStatus(userId));
    }

    @Operation(
            summary = "Accept a consent",
            description = "Records the authenticated user's acceptance of a specific consent. Authenticated user only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent accepted successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/accept")
    public ResponseEntity<UserConsentResponse> acceptConsent(
            Authentication authentication,
            HttpServletRequest servletRequest,
            @Valid @RequestBody AcceptConsentRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        UserConsentResponse response = consentService.acceptConsent(
                userId,
                request,
                resolveClientIp(servletRequest),
                servletRequest.getHeader("User-Agent")
        );

        return ResponseEntity.ok(response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    @Operation(
            summary = "Accept required traveler consents",
            description = "Records the authenticated user's acceptance of all consents required to act as a traveler. Authenticated user only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Required traveler consents accepted successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/accept-required-traveler")
    public ResponseEntity<List<UserConsentResponse>> acceptRequiredTravelerConsents(
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        List<UserConsentResponse> response = consentService.acceptRequiredTravelerConsents(
                userId,
                resolveClientIp(servletRequest),
                servletRequest.getHeader("User-Agent")
        );

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Accept required local consents",
            description = "Records the authenticated user's acceptance of all consents required to act as a local host. Authenticated user only."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Required local consents accepted successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/accept-required-local")
    public ResponseEntity<List<UserConsentResponse>> acceptRequiredLocalConsents(
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID userId = UUID.fromString(authentication.getName());

        List<UserConsentResponse> response = consentService.acceptRequiredLocalConsents(
                userId,
                resolveClientIp(servletRequest),
                servletRequest.getHeader("User-Agent")
        );

        return ResponseEntity.ok(response);
    }
}