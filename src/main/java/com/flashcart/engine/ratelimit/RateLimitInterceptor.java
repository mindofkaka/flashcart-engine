package com.flashcart.engine.ratelimit;

import com.flashcart.engine.config.RateLimitProperties;
import com.flashcart.engine.metrics.FlashCartMetrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final PathPattern PURCHASE =
            PathPatternParser.defaultInstance.parse("/api/v1/products/{id}/purchase");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String USER_HEADER = "X-User-Id"; // placeholder until Spring Security

    private final RedisRateLimiter limiter;
    private final RateLimitProperties props;
    private final FlashCartMetrics metrics;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (!props.enabled() || "OPTIONS".equals(request.getMethod())) {
            return true;
        }

        // Level 1: per IP (cheapest check first, rejects most abuse early)
        var ipRule = props.ip();
        var ipResult = limiter.tryConsume("flashcart:rl:ip:" + request.getRemoteAddr(), ipRule);
        if (!ipResult.allowed()) {
            return reject(response, ipResult, ipRule.capacity(), "ip");
        }

        // Level 2: per user, purchase endpoint only
        if ("POST".equals(request.getMethod()) && PURCHASE.matches(PathContainer.parsePath(request.getRequestURI()))) {
            String userId = request.getHeader(USER_HEADER);
            if (userId != null && SAFE_ID.matcher(userId).matches()) {
                var userRule = props.user();
                var userResult = limiter.tryConsume("flashcart:rl:user:" + userId, userRule);
                if (!userResult.allowed()) {
                    return reject(response, userResult, userRule.capacity(), "user");
                }
            }
        }

        if (ipResult.remaining() >= 0) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(ipRule.capacity()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(ipResult.remaining()));
        }
        return true;
    }

    private boolean reject(HttpServletResponse res, RedisRateLimiter.Result r, int limit, String scope)
            throws IOException {
        metrics.rateLimitBlocked(scope);
        long retrySec = Math.max(1, (long) Math.ceil(r.retryAfterMs() / 1000.0));

        res.setStatus(429);
        res.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retrySec));
        res.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        res.setHeader("X-RateLimit-Remaining", "0");
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"Rate limit exceeded (%s). Retry after %d second(s).",\
                "retryAfterSeconds":%d}""".formatted(scope, retrySec, retrySec));
        return false; // short-circuit: controller never runs
    }
}