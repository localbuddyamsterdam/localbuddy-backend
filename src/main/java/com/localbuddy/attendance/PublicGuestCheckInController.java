package com.localbuddy.attendance;

import com.localbuddy.ratelimit.ClientIpResolver;
import com.localbuddy.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous-guest geo check-in (booking reference + email), rate-limited per IP. */
@RestController
@RequestMapping("/api/public/check-in")
public class PublicGuestCheckInController {

    private final AttendanceService attendanceService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public PublicGuestCheckInController(AttendanceService attendanceService,
                                        RateLimitService rateLimitService,
                                        ClientIpResolver clientIpResolver) {
        this.attendanceService = attendanceService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping
    public ResponseEntity<CheckInResponse> checkIn(
            HttpServletRequest servletRequest,
            @Valid @RequestBody GuestCheckInRequest request
    ) {
        String clientIp = clientIpResolver.resolveClientIp(servletRequest);
        rateLimitService.checkPublicApiLimit("guest-check-in:" + clientIp);
        return ResponseEntity.ok(attendanceService.guestCheckInAnonymous(request));
    }
}
