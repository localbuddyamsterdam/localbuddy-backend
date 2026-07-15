package com.localbuddy.ai;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/public/experiences")
@Tag(name = "AI Review Summaries", description = "Cached AI digests of traveler reviews per experience")
public class PublicReviewSummaryController {

    private final ReviewSummaryService reviewSummaryService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicReviewSummaryController(
            ReviewSummaryService reviewSummaryService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver
    ) {
        this.reviewSummaryService = reviewSummaryService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "AI review summary",
            description = "\"What guests say\": a cached AI digest of the experience's visible reviews. "
                    + "available=false when there are not enough reviews yet.")
    @GetMapping("/{experienceId}/review-summary")
    public ResponseEntity<ReviewSummaryResponse> getReviewSummary(
            HttpServletRequest servletRequest,
            @PathVariable UUID experienceId
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("ai-review-summary:" + clientIp);
        return ResponseEntity.ok(reviewSummaryService.getSummary(experienceId));
    }
}
