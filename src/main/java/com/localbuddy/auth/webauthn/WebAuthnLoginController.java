package com.localbuddy.auth.webauthn;

import com.localbuddy.auth.LoginResponse;
import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public passkey sign-in — lives under {@code /api/auth/**} (permitAll) like password login. */
@RestController
@RequestMapping("/api/auth/webauthn")
@Tag(name = "Authentication", description = "Signup, login, social login, session, and current-user endpoints")
public class WebAuthnLoginController {

    private final WebAuthnService webAuthnService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public WebAuthnLoginController(WebAuthnService webAuthnService,
                                   RateLimitService rateLimitService,
                                   ClientIpResolver clientIpResolver) {
        this.webAuthnService = webAuthnService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Start a passkey sign-in",
            description = "Public (no auth). Issues an assertion challenge for navigator.credentials.get(). "
                    + "With an email, includes that account's credential ids; without one, runs a userless "
                    + "(discoverable-credential) ceremony."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sign-in options issued")
    })
    @PostMapping("/options")
    public ResponseEntity<WebAuthnAuthenticationOptionsResponse> options(
            HttpServletRequest servletRequest,
            @RequestBody(required = false) WebAuthnAuthenticationOptionsRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-webauthn-options:" + clientIpResolver.resolveClientIp(servletRequest));
        String email = request == null ? null : request.email();
        return ResponseEntity.ok(webAuthnService.authenticationOptions(email));
    }

    @Operation(
            summary = "Sign in with a passkey",
            description = "Public (no auth). Verifies the authenticator's assertion and returns an access token "
                    + "plus a refresh token, exactly like password login."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Passkey verification failed")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            HttpServletRequest servletRequest,
            @Valid @RequestBody WebAuthnLoginRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-webauthn-login:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(webAuthnService.completeAuthentication(request));
    }
}
