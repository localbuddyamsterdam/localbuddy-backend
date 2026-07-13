package com.localbuddy.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Signup, login, social login, and current-user endpoints")
public class AuthController {

    private final AuthService authService;
    private final SocialAuthService socialAuthService;

    public AuthController(AuthService authService, SocialAuthService socialAuthService) {
        this.authService = authService;
        this.socialAuthService = socialAuthService;
    }

    @Operation(
            summary = "Sign up a new user",
            description = "Public (no auth). Registers a new user account and returns authentication details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or email already in use")
    })
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Log in with email and password",
            description = "Public (no auth). Authenticates a user and returns an access token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Log in with a social provider",
            description = "Public (no auth). Authenticates a user via a social identity provider token and returns an access token."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Social login successful"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Invalid or unverifiable social token")
    })
    @PostMapping("/social")
    public ResponseEntity<LoginResponse> socialLogin(@Valid @RequestBody SocialLoginRequest request) {
        return ResponseEntity.ok(socialAuthService.socialLogin(request));
    }

    @Operation(
            summary = "Refresh the access token",
            description = "Authenticated user. Re-issues a fresh access token for a still-valid session (sliding session)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token refreshed"),
            @ApiResponse(responseCode = "401", description = "Session expired or invalid — sign in again")
    })
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(authService.refreshToken(userId));
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