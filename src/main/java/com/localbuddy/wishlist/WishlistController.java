package com.localbuddy.wishlist;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/wishlist")
@Tag(name = "Wishlist", description = "Traveler's saved/favorite experiences")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @Operation(summary = "Get my wishlist")
    @GetMapping
    public ResponseEntity<List<WishlistItemResponse>> getMyWishlist(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(wishlistService.getMyWishlist(userId));
    }

    @Operation(summary = "Add an experience to my wishlist")
    @PostMapping("/{experienceId}")
    public ResponseEntity<WishlistItemResponse> addToWishlist(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wishlistService.addToWishlist(userId, experienceId));
    }

    @Operation(summary = "Remove an experience from my wishlist")
    @DeleteMapping("/{experienceId}")
    public ResponseEntity<Void> removeFromWishlist(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        wishlistService.removeFromWishlist(userId, experienceId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Check if an experience is in my wishlist")
    @GetMapping("/{experienceId}/status")
    public ResponseEntity<Map<String, Boolean>> isInWishlist(
            Authentication authentication,
            @PathVariable UUID experienceId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(Map.of("inWishlist", wishlistService.isInWishlist(userId, experienceId)));
    }
}
