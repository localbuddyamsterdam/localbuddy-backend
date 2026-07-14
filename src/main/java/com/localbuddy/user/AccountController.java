package com.localbuddy.user;

import com.localbuddy.auth.AuthService;
import com.localbuddy.auth.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Self-service account endpoints for the authenticated user (any role).
 * Lives under /api/account so it is not caught by the admin-only /api/users rules.
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "Account", description = "Self-service account management for authenticated users")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final UserService userService;
    private final AuthService authService;

    public AccountController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMyProfile(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(userService.getUserById(userId));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateMyProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(userService.updateOwnProfile(userId, request));
    }

    @Operation(summary = "Upload my avatar",
            description = "Uploads an image (JPEG/PNG/WebP/GIF, max 5 MB) to blob storage and sets it as the "
                    + "account avatar. Requires Azure Blob storage to be configured.")
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> uploadMyAvatar(
            Authentication authentication,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(userService.uploadMyAvatar(
                userId, file.getBytes(), file.getContentType(), file.getOriginalFilename()));
    }

    @Operation(summary = "Delete my avatar",
            description = "Removes the account avatar: deletes the stored blob and clears the URL.")
    @DeleteMapping("/me/avatar")
    public ResponseEntity<UserResponse> deleteMyAvatar(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(userService.deleteMyAvatar(userId));
    }

    @Operation(summary = "Change password",
            description = "Changes the password for the authenticated user. Requires the current password for verification.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request or current password is incorrect"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        userService.changePassword(userId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set initial password (forced first-login change)",
            description = "For a user who logged in with an admin-issued temporary password. Sets their "
                    + "own password without re-collecting the temporary one, clears the must-change flag, "
                    + "revokes existing sessions, and returns a fresh session. Only valid while the account "
                    + "is flagged as must-change-password.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password set; a fresh session is returned"),
            @ApiResponse(responseCode = "400", description = "Weak password, or no password change is required"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/me/set-initial-password")
    public ResponseEntity<LoginResponse> setInitialPassword(
            Authentication authentication,
            @Valid @RequestBody SetInitialPasswordRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(authService.setInitialPassword(userId, request.newPassword()));
    }
}
