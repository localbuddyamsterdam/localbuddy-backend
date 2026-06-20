package com.localbuddy.deals;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/deals")
@Tag(name = "Public - Deals", description = "Public endpoints for browsing currently-live deals (no authentication required)")
public class PublicDealController {

    private final DealService dealService;

    public PublicDealController(DealService dealService) {
        this.dealService = dealService;
    }

    @Operation(
            summary = "List currently-live deals",
            description = "Returns deals that are active and within their time window, ordered by priority. "
                    + "Optional filters narrow results to global deals plus those matching the given city, "
                    + "experience, category or deal type."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Currently-live deals returned")
    })
    @GetMapping
    public ResponseEntity<List<DealResponse>> listLiveDeals(
            @RequestParam(value = "dealType", required = false) DealType dealType,
            @RequestParam(value = "cityId", required = false) UUID cityId,
            @RequestParam(value = "experienceId", required = false) UUID experienceId,
            @RequestParam(value = "categoryId", required = false) UUID categoryId
    ) {
        return ResponseEntity.ok(
                dealService.listLiveDeals(dealType, cityId, experienceId, categoryId)
        );
    }
}
