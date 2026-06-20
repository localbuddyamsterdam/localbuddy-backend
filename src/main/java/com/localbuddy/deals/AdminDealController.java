package com.localbuddy.deals;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/deals")
public class AdminDealController {

    private final DealService dealService;

    public AdminDealController(DealService dealService) {
        this.dealService = dealService;
    }

    @PostMapping
    public ResponseEntity<DealResponse> createDeal(
            @Valid @RequestBody CreateDealRequest request
    ) {
        DealResponse response = dealService.createDeal(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<DealResponse>> listDeals() {
        return ResponseEntity.ok(dealService.listAll());
    }

    @GetMapping("/{dealId}")
    public ResponseEntity<DealResponse> getDeal(
            @PathVariable UUID dealId
    ) {
        return ResponseEntity.ok(dealService.getById(dealId));
    }

    @PutMapping("/{dealId}")
    public ResponseEntity<DealResponse> updateDeal(
            @PathVariable UUID dealId,
            @Valid @RequestBody UpdateDealRequest request
    ) {
        return ResponseEntity.ok(dealService.updateDeal(dealId, request));
    }

    @PostMapping("/{dealId}/deactivate")
    public ResponseEntity<DealResponse> deactivateDeal(
            @PathVariable UUID dealId
    ) {
        return ResponseEntity.ok(dealService.deactivate(dealId));
    }
}
