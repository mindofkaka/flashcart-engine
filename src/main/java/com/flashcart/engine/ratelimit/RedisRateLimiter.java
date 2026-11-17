package com.flashcart.engine.ratelimit;

import com.flashcart.engine.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRateLimiter {

    /** remaining = -1 means "unknown" (Redis failure, fail-open). */
    public record Result(boolean allowed, long remaining, long retryAfterMs) {}

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> rateLimitScript;
    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public Result tryConsume(String key, RateLimitProperties.Rule rule) {
        try {
            List<?> r = redis.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(rule.capacity()),
                    String.valueOf(rule.refillPerSecond()),
                    "1");
            if (r == null || r.size() < 3) throw new IllegalStateException("Bad script result");
            return new Result(
                    ((Number) r.get(0)).longValue() == 1,
                    ((Number) r.get(1)).longValue(),
                    ((Number) r.get(2)).longValue());
        } catch (RuntimeException e) {
            log.error("Rate limiter failed for key {}", key, e);
            return props.failOpen()
                    ? new Result(true, -1, 0)
                    : new Result(false, 0, 1000);
        }
    }
}