package com.localbuddy.admin;

import com.localbuddy.user.AdminChangeRoleRequest;
import com.localbuddy.user.AdminTempPasswordResponse;
import com.localbuddy.user.AdminUserCreatedResponse;
import com.localbuddy.user.AdminUserResponse;
import com.localbuddy.user.AdminUserService;
import com.localbuddy.user.AdminUserStatusRequest;
import com.localbuddy.user.CreateUserRequest;
import com.localbuddy.user.StaffRoleAuditResponse;
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

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin - Users", description = "Admin endpoints for listing users and managing their account status; "
        + "team management (staff creation, role changes) is a super admin privilege")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** The acting admin's user id — the JWT principal is the user UUID (see JwtAuthenticationFilter). */
    private static UUID actorId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    @Operation(
            summary = "List all users",
            description = "Admin only. Returns all registered users with their administrative details."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Users retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    })
    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> getUsers() {
        return ResponseEntity.ok(adminUserService.getUsers());
    }

    @Operation(
            summary = "Create a user with a temporary password",
            description = "Admin only. Creates an active user and generates a temporary password, "
                    + "returned once in the response so the admin can relay it. The user is forced to "
                    + "set their own password on first login. Creating staff accounts (SUPPORT/ADMIN/"
                    + "SUPER_ADMIN) requires the caller to be a super admin."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created; temporary password returned once"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or email already in use"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin, or not a super admin for staff roles")
    })
    @PostMapping
    public ResponseEntity<AdminUserCreatedResponse> createUser(
            Authentication authentication,
            @Valid @RequestBody CreateUserRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminUserService.createUser(actorId(authentication), request));
    }

    @Operation(
            summary = "Reset a user's password",
            description = "Generates a temporary password (returned once), revokes the user's active "
                    + "sessions, and forces a password change on next login. Admin/support targets require "
                    + "a super admin caller; super admin passwords can never be reset here — only the "
                    + "account holder can set one (self-service reset)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Temporary password generated and returned once"),
            @ApiResponse(responseCode = "400", description = "User cannot be modified (e.g. deleted)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Insufficient tier for this target"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping("/{userId}/reset-password")
    public ResponseEntity<AdminTempPasswordResponse> resetPassword(
            Authentication authentication,
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(adminUserService.resetPassword(actorId(authentication), userId));
    }

    @Operation(
            summary = "Change a user's role (super admin only)",
            description = "Super admin only. Promote a traveller to staff (SUPPORT/ADMIN/SUPER_ADMIN), "
                    + "demote a super admin to admin, or remove a staff member (back to LOGGED_IN_USER). "
                    + "You can never change your own role, and the last active super admin can never be "
                    + "demoted — so the platform always keeps at least one super admin."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role changed"),
            @ApiResponse(responseCode = "400", description = "Invalid target role or invariant violated"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not a super admin, or tried to change their own role"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping("/{userId}/role")
    public ResponseEntity<AdminUserResponse> changeRole(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminChangeRoleRequest request
    ) {
        return ResponseEntity.ok(adminUserService.changeRole(actorId(authentication), userId, request.role()));
    }

    @Operation(
            summary = "Admin-team audit trail (super admin only)",
            description = "Super admin only. The 50 most recent admin-team changes: staff created, roles "
                    + "changed, staff removed, temporary passwords issued, bootstrap/break-glass actions."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit entries returned"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not a super admin")
    })
    @GetMapping("/team-audit")
    public ResponseEntity<List<StaffRoleAuditResponse>> getTeamAudit(Authentication authentication) {
        return ResponseEntity.ok(adminUserService.getTeamAudit(actorId(authentication)));
    }

    @Operation(
            summary = "Get a user by ID",
            description = "Admin only. Returns the administrative details for a single user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserResponse> getUserById(
            @PathVariable UUID userId
    ) {
        return ResponseEntity.ok(adminUserService.getUserById(userId));
    }

    @Operation(
            summary = "Update a user's status",
            description = "Admin only. Updates the account status (e.g. active/suspended) of the given user. "
                    + "Admin-tier accounts cannot have their status changed from this API."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User status updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Caller is not an admin"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping("/{userId}/status")
    public ResponseEntity<AdminUserResponse> updateUserStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserStatusRequest request
    ) {
        return ResponseEntity.ok(adminUserService.updateUserStatus(userId, request));
    }
}
