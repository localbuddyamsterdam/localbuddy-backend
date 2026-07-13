package com.localbuddy.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    // Only trust X-Forwarded-For / X-Real-IP when the app sits behind a TRUSTED proxy that sets them.
    // Default false: these headers are client-controlled and trivially spoofable, so trusting them
    // unconditionally lets a caller forge a fresh IP per request and bypass IP-based rate limiting.
    // Enable in production only when a known reverse proxy / load balancer overwrites them.
    private final boolean trustForwardedHeaders;

    public ClientIpResolver(
            @Value("${app.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }

            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }

        return request.getRemoteAddr();
    }
}
