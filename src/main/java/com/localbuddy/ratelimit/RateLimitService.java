package com.localbuddy.ratelimit;

import com.localbuddy.common.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RateLimitService(StringRedisTemplate redisTemplate,
                            RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkPublicApiLimit(String key) {
        if (!properties.enabled()) {
            return;
        }

        String redisKey = "rate-limit:public:" + key;

        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);

            if (count != null && count == 1) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(properties.windowSeconds()));
            }

            if (count != null && count > properties.maxRequests()) {
                throw new RateLimitExceededException("Too many requests. Please try again later.");
            }

        } catch (RateLimitExceededException ex) {
            // Limit exceeded — propagate to the caller (mapped to HTTP 429).
            throw ex;
        } catch (RuntimeException ex) {
            // Redis/infrastructure failure: fail open so the core app keeps working, but make the
            // outage visible (was previously swallowed silently) so it can be alerted on.
            log.warn("Rate limit check skipped (Redis/infra error) for key '{}': {}", redisKey, ex.getMessage());
        }
    }
}
