package com.localbuddy.auth.webauthn;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Passkey enrollment and management for the signed-in user. Sign-in itself is public and lives in
 * {@link WebAuthnLoginController}; this controller sits outside {@code /api/auth/**} so Spring
 * Security's default authenticated rule applies.
 */
@RestController
@RequestMapping("/api/webauthn")
@Tag(name = "Passkeys", description = "Enroll and manage passkeys (Face ID / Touch ID / device passcode sign-in)")
public class WebAuthnController {

    private final WebAuthnService webAuthnService;

    public WebAuthnController(WebAuthnService webAuthnService) {
        this.webAuthnService = webAuthnService;
    }

    @Operation(
            summary = "Start passkey enrollment",
            description = "Authenticated user. Issues a registration challenge plus the relying-party and user "
                    + "entities the browser needs for navigator.credentials.create()."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Enrollment options issued"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/register/options")
    public ResponseEntity<WebAuthnRegistrationOptionsResponse> registrationOptions(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(webAuthnService.registrationOptions(userId));
    }

    @Operation(
            summary = "Finish passkey enrollment",
            description = "Authenticated user. Verifies the authenticator's response and stores the new passkey."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Passkey registered"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired enrollment response"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/register")
    public ResponseEntity<WebAuthnCredentialResponse> register(
            Authentication authentication,
            @Valid @RequestBody WebAuthnRegisterRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        WebAuthnCredentialResponse response = webAuthnService.completeRegistration(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "List the user's passkeys",
            description = "Authenticated user. Returns the registered passkeys for account settings."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Passkeys listed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/credentials")
    public ResponseEntity<List<WebAuthnCredentialResponse>> list(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(webAuthnService.listCredentials(userId));
    }

    @Operation(
            summary = "Remove a passkey",
            description = "Authenticated user. Deletes one of the user's own passkeys. The credential also remains "
                    + "on the device/keychain until the user removes it there, but it can no longer sign in."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Passkey removed"),
            @ApiResponse(responseCode = "400", description = "Passkey not found"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        UUID userId = UUID.fromString(authentication.getName());
        webAuthnService.deleteCredential(userId, id);
        return ResponseEntity.noContent().build();
    }
}
