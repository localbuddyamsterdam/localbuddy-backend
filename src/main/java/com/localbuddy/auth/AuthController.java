package com.localbuddy.auth;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Signup, login, social login, session, and current-user endpoints")
public class AuthController {

    private final AuthService authService;
    private final SocialAuthService socialAuthService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService,
                          SocialAuthService socialAuthService,
                          RateLimitService rateLimitService,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.socialAuthService = socialAuthService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Sign up a new user",
            description = "Public (no auth). Registers a new user account, sends a verification email, and returns authentication details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or email already in use")
    })
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            HttpServletRequest servletRequest,
            @Valid @RequestBody SignupRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-signup:" + clientIpResolver.resolveClientIp(servletRequest));
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Check whether an email is registered",
            description = "Public (no auth). Powers the email-first sign-in flow: returns whether an account "
                    + "exists for the email and whether it has a password (false for social-only accounts). "
                    + "Rate-limited per client IP."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lookup completed"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping("/check-email")
    public ResponseEntity<CheckEmailResponse> checkEmail(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CheckEmailRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-check-email:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(authService.checkEmail(request.email()));
    }

    @Operation(
            summary = "Log in with email and password",
            description = "Public (no auth). Authenticates a user and returns an access token plus a refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            HttpServletRequest servletRequest,
            @Valid @RequestBody LoginRequest request
    ) {
        String ip = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("auth-login:" + ip);
        rateLimitService.checkPublicApiLimit("auth-login-email:" + request.email().trim().toLowerCase());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Log in with a social provider",
            description = "Public (no auth). Authenticates a user via a social identity provider token and returns an access token plus a refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Social login successful"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Invalid or unverifiable social token")
    })
    @PostMapping("/social")
    public ResponseEntity<LoginResponse> socialLogin(
            HttpServletRequest servletRequest,
            @Valid @RequestBody SocialLoginRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-social:" + clientIpResolver.resolveClientIp(servletRequest));
        return ResponseEntity.ok(socialAuthService.socialLogin(request));
    }

    @Operation(
            summary = "Refresh the session",
            description = "Public (no auth). Exchanges a valid refresh token for a new access token and a rotated refresh token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session refreshed"),
            @ApiResponse(responseCode = "401", description = "Session expired or invalid — sign in again")
    })
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshSession(request.refreshToken()));
    }

    @Operation(
            summary = "Log out",
            description = "Public (no auth). Revokes the supplied refresh token so it can no longer be used."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logged out"),
            @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Request a password reset",
            description = "Public (no auth). If an account exists for the email, sends a reset link. Always returns 204 (existence is never revealed)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Request accepted")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-forgot:" + clientIpResolver.resolveClientIp(servletRequest));
        authService.forgotPassword(request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Reset a password",
            description = "Public (no auth). Consumes a valid reset token and sets a new password, revoking all existing sessions."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired reset link, or weak password")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-reset:" + clientIpResolver.resolveClientIp(servletRequest));
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Verify an email address",
            description = "Public (no auth). Consumes a valid verification token and marks the account's email verified."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Email verified"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired verification link")
    })
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Resend a verification email",
            description = "Public (no auth). If an unverified account exists for the email, re-sends the verification link. Always returns 204."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Request accepted")
    })
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            HttpServletRequest servletRequest,
            @Valid @RequestBody ResendVerificationRequest request
    ) {
        rateLimitService.checkPublicApiLimit("auth-resend:" + clientIpResolver.resolveClientIp(servletRequest));
        authService.resendVerification(request.email());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get the current user",
            description = "Authenticated user. Returns the profile of the currently authenticated user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        CurrentUserResponse response = authService.getCurrentUser(userId);
        return ResponseEntity.ok(response);
    }
}
