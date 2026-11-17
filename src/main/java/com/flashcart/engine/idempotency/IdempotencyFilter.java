package com.flashcart.engine.idempotency;

import com.flashcart.engine.config.IdempotencyProperties;
import com.flashcart.engine.metrics.FlashCartMetrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

// Not a @Component: it is registered in IdempotencyConfig
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final PathPattern PURCHASE =
            PathPatternParser.defaultInstance.parse("/api/v1/products/{id}/purchase");
    private static final String HEADER = "Idempotency-Key";
    private static final String PREFIX = "flashcart:idempotency:";

    private final StringRedisTemplate redis;
    private final IdempotencyProperties props;
    private final FlashCartMetrics metrics;   

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled()
                || !"POST".equals(request.getMethod())
                || !PURCHASE.matches(PathContainer.parsePath(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // --- validate header ---
        UUID key;
        try {
            String raw = request.getHeader(HEADER);
            if (raw == null) throw new IllegalArgumentException();
            key = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            problem(response, 400, "Bad Request", "Header 'Idempotency-Key' (UUID) is required");
            return;
        }

        String redisKey = PREFIX + key;
        String fingerprint = sha256(request.getMethod() + " " + request.getRequestURI());

        // --- State 1: atomic acquire (SET key value NX PX ttl) ---
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING|" + fingerprint, props.processingTtl());

        if (!Boolean.TRUE.equals(acquired)) {
            handleExisting(redisKey, fingerprint, response);
            return;
        }

        // --- we own the key: run the controller and capture its response ---
        var wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(request, wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                // overwrite PROCESSING with COMPLETED + longer TTL
                redis.opsForValue().set(redisKey,
                        "COMPLETED|" + fingerprint + "|" + status + "|" + body,
                        props.completedTtl());
                completed = true;
            }
        } finally {
            // failures (4xx/5xx, exceptions, 429 from the rate limiter) release the key,
            // so the client can retry with the same Idempotency-Key
            if (!completed) redis.delete(redisKey);
            wrapped.copyBodyToResponse();   // always flush the captured body to the client
        }
    }

    private void handleExisting(String redisKey, String fingerprint, HttpServletResponse res)
            throws IOException {
        String existing = redis.opsForValue().get(redisKey);
        if (existing == null) {   // expired or released between our SETNX and GET
            problem(res, 409, "Conflict", "Request state changed, please retry");
            return;
        }
        // PROCESSING|fp  or  COMPLETED|fp|status|body
        String[] p = existing.split("\\|", 4);

        if (!p[1].equals(fingerprint)) {
            problem(res, 422, "Unprocessable Content",
                    "Idempotency-Key was already used for a different request");
        } else if ("PROCESSING".equals(p[0])) {
            metrics.idempotencyConflict();
            res.setHeader("Retry-After", "1");
            problem(res, 409, "Conflict", "Request already in progress");
        } else {   // COMPLETED: replay, no DB, no stream, no stock change
            metrics.idempotencyHit();
            res.setStatus(Integer.parseInt(p[2]));
            res.setHeader("Idempotency-Replayed", "true");
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write(p[3]);
        }
    }

    private void problem(HttpServletResponse res, int status, String title, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(title, status, detail));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}