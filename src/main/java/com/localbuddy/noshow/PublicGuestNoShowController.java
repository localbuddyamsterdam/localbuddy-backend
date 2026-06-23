package com.localbuddy.noshow;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/guest-no-show")
@Tag(name = "Public - Guest No-show", description = "Public, rate-limited endpoint for guests to report a host no-show (no authentication required)")
public class PublicGuestNoShowController {

    private final NoShowService noShowService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicGuestNoShowController(NoShowService noShowService,
                                       RateLimitService rateLimitService,
                                       ClientIpResolver clientIpResolver) {
        this.noShowService = noShowService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(
            summary = "Report a host no-show as a guest",
            description = "A guest customer (no account) reports that the host did not show up, verified by booking "
                    + "reference + guest email (refund claim, admin-reviewed). Allowed up to 48h after the experience "
                    + "start time. No authentication required. Rate-limited per client IP."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Host no-show report created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or booking not reportable"),
            @ApiResponse(responseCode = "404", description = "Guest booking not found"),
            @ApiResponse(responseCode = "429", description = "Too many requests (rate limit exceeded)")
    })
    @PostMapping("/report")
    public ResponseEntity<NoShowReportResponse> reportHostNoShow(
            HttpServletRequest servletRequest,
            @Valid @RequestBody GuestNoShowReportRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("guest-no-show:" + clientIp);

        NoShowReportResponse response = noShowService.reportHostNoShowAsGuest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
