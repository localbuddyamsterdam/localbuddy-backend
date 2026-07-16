package com.localbuddy.tripplan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Trip Genie funnel for admins. Route is under /api/admin/** so ROLE_ADMIN is enforced by
 * the URL-pattern security rules.
 */
@RestController
@RequestMapping("/api/admin/trip-plans")
@Tag(name = "Admin: AI Trip Planner", description = "Trip Genie funnel and model quality metrics")
@SecurityRequirement(name = "bearerAuth")
public class AdminTripPlanController {

    private final TripPlanRepository tripPlanRepository;

    public AdminTripPlanController(TripPlanRepository tripPlanRepository) {
        this.tripPlanRepository = tripPlanRepository;
    }

    @Operation(summary = "Trip Genie funnel",
            description = "For plans generated in the last N days: how many were re-viewed, reached a bundle "
                    + "checkout, and were paid — plus helpfulness votes and token spend per model.")
    @GetMapping("/funnel")
    @Transactional(readOnly = true)
    public ResponseEntity<TripPlanFunnelResponse> funnel(
            @RequestParam(defaultValue = "30") int days
    ) {
        int windowDays = Math.max(1, Math.min(days, 365));
        Instant from = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        List<TripPlanFunnelResponse.ModelStat> byModel = tripPlanRepository.funnelByModel(from).stream()
                .map(row -> new TripPlanFunnelResponse.ModelStat(
                        row.getModel() != null ? row.getModel() : "(unknown)",
                        row.getPlans(),
                        row.getHelpful(),
                        row.getUnhelpful(),
                        row.getAvgOutputTokens() != null ? (int) Math.round(row.getAvgOutputTokens()) : null
                ))
                .toList();

        return ResponseEntity.ok(new TripPlanFunnelResponse(
                windowDays,
                tripPlanRepository.countByCreatedAtGreaterThanEqual(from),
                tripPlanRepository.countReViewed(from),
                tripPlanRepository.countWithCheckoutStarted(from),
                tripPlanRepository.countWithCheckoutPaid(from),
                tripPlanRepository.countFeedback(from, true),
                tripPlanRepository.countFeedback(from, false),
                tripPlanRepository.sumInputTokens(from),
                tripPlanRepository.sumOutputTokens(from),
                byModel
        ));
    }
}
