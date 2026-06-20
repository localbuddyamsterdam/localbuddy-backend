package com.localbuddy.ratelimit;

import com.localbuddy.common.exception.BadRequestException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

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
                throw new BadRequestException("Too many requests. Please try again later.");
            }

        } catch (BadRequestException ex) {
            // Rate limit exceeded — propagate to the caller.
            throw ex;
        } catch (RuntimeException ex) {
            // Any Redis/infrastructure failure: fail open so the core app keeps working.
        }
    }
}