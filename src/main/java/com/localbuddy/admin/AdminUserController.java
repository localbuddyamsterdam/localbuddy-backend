package com.localbuddy.admin;

import com.localbuddy.user.AdminUserResponse;
import com.localbuddy.user.AdminUserService;
import com.localbuddy.user.AdminUserStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin - Users", description = "Admin endpoints for listing users and managing their account status")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
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
            description = "Admin only. Updates the account status (e.g. active/suspended) of the given user."
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