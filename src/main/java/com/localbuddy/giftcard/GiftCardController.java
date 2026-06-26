package com.localbuddy.giftcard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/gift-cards")
@Tag(name = "Gift Cards", description = "Purchase, view, and redeem gift cards")
public class GiftCardController {

    private final GiftCardService giftCardService;

    public GiftCardController(GiftCardService giftCardService) {
        this.giftCardService = giftCardService;
    }

    @Operation(summary = "Purchase a gift card",
            description = "Creates a pending gift card and returns a Stripe checkout URL; the card activates once payment completes.")
    @PostMapping("/purchase")
    public ResponseEntity<GiftCardPurchaseResponse> purchase(
            Authentication authentication,
            @Valid @RequestBody PurchaseGiftCardRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(giftCardService.purchase(userId, request));
    }

    @Operation(summary = "List gift cards I purchased")
    @GetMapping("/me")
    public ResponseEntity<List<GiftCardResponse>> getMyGiftCards(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(giftCardService.getMyGiftCards(userId));
    }

    @Operation(summary = "Redeem a gift card", description = "Deducts an amount from a gift card's balance.")
    @PostMapping("/redeem")
    public ResponseEntity<GiftCardBalanceResponse> redeem(
            Authentication authentication,
            @Valid @RequestBody RedeemGiftCardRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(giftCardService.redeem(userId, request));
    }
}
