package com.localbuddy.localprofile;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/local-profiles/me/tax-info")
@Tag(name = "Host tax info", description = "Host VAT and DAC7 tax details")
@SecurityRequirement(name = "bearerAuth")
public class HostTaxInfoController {

    private final HostTaxInfoService hostTaxInfoService;

    public HostTaxInfoController(HostTaxInfoService hostTaxInfoService) {
        this.hostTaxInfoService = hostTaxInfoService;
    }

    @GetMapping
    public ResponseEntity<HostTaxInfoResponse> get(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(hostTaxInfoService.getForUser(userId));
    }

    @PutMapping
    public ResponseEntity<HostTaxInfoResponse> update(
            Authentication authentication,
            @Valid @RequestBody HostTaxInfoRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(hostTaxInfoService.update(userId, request));
    }
}
