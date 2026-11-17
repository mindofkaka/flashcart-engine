package com.flashcart.engine.metrics;

import com.flashcart.engine.entity.OrderStatus;
import com.flashcart.engine.stream.StreamKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class FlashCartMetrics {

    private final StringRedisTemplate redis;

    private final Counter accepted;
    private final Counter outOfStock;
    private final Counter idempotencyHits;
    private final Counter idempotencyConflicts;
    private final Counter rateLimitIp;
    private final Counter rateLimitUser;
    private final Counter persistedConfirmed;
    private final Counter persistedRejected;

    private final AtomicLong streamLag = new AtomicLong();
    private final AtomicLong streamPending = new AtomicLong();
    private final AtomicLong dlqSize = new AtomicLong();

    public FlashCartMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.redis = redis;

        accepted = Counter.builder("flashcart.orders.accepted")
                .description("Stock decremented in Redis and event published to the stream")
                .register(registry);
        outOfStock = Counter.builder("flashcart.orders.rejected.out_of_stock")
                .description("Fast-failed by the Redis Lua script")
                .register(registry);
        idempotencyHits = Counter.builder("flashcart.idempotency.hits")
                .description("Duplicate requests answered from the idempotency cache")
                .register(registry);
        idempotencyConflicts = Counter.builder("flashcart.idempotency.conflicts")
                .description("Duplicates rejected because the original is still processing")
                .register(registry);
        rateLimitIp = Counter.builder("flashcart.ratelimit.blocked").tag("scope", "ip")
                .description("Requests rejected with 429").register(registry);
        rateLimitUser = Counter.builder("flashcart.ratelimit.blocked").tag("scope", "user")
                .description("Requests rejected with 429").register(registry);
        persistedConfirmed = Counter.builder("flashcart.orders.persisted").tag("status", "CONFIRMED")
                .description("Orders written to Postgres by the stream worker").register(registry);
        persistedRejected = Counter.builder("flashcart.orders.persisted").tag("status", "REJECTED")
                .description("Orders written to Postgres by the stream worker").register(registry);

        Gauge.builder("flashcart.stream.lag", streamLag, AtomicLong::get)
                .description("Messages not yet delivered to any worker").register(registry);
        Gauge.builder("flashcart.stream.pending", streamPending, AtomicLong::get)
                .description("Messages delivered but not yet acknowledged").register(registry);
        Gauge.builder("flashcart.stream.dlq.size", dlqSize, AtomicLong::get)
                .description("Messages moved to the dead-letter stream").register(registry);
    }

    // ---- methods called from the rest of the app ----

    public void orderAccepted()       { accepted.increment(); }
    public void outOfStock()          { outOfStock.increment(); }
    public void idempotencyHit()      { idempotencyHits.increment(); }
    public void idempotencyConflict() { idempotencyConflicts.increment(); }

    public void rateLimitBlocked(String scope) {
        ("ip".equals(scope) ? rateLimitIp : rateLimitUser).increment();
    }

    public void orderPersisted(OrderStatus status) {
        (status == OrderStatus.CONFIRMED ? persistedConfirmed : persistedRejected).increment();
    }

    // ---- stream gauges ----

    @Scheduled(fixedDelay = 2000)
    void refreshStreamGauges() {
        try {
            var ops = redis.opsForStream();

            var summary = ops.pending(StreamKeys.ORDERS, StreamKeys.GROUP);
            streamPending.set(summary == null ? 0 : summary.getTotalPendingMessages());

            streamLag.set(readLag());

            Long dlq = ops.size(StreamKeys.ORDERS_DLQ);
            dlqSize.set(dlq == null ? 0 : dlq);
        } catch (RuntimeException e) {
            log.warn("Could not refresh stream gauges: {}", e.getMessage());
        }
    }

    /** Raw XINFO GROUPS <stream>; returns the "lag" field of our consumer group. */
    private long readLag() {
        List<?> groups = redis.execute((RedisCallback<List<?>>) c -> {
            Object r = c.execute("XINFO",
                    "GROUPS".getBytes(StandardCharsets.UTF_8),
                    StreamKeys.ORDERS.getBytes(StandardCharsets.UTF_8));
            return r instanceof List<?> l ? l : List.of();
        });
        if (groups == null) return 0;

        for (Object g : groups) {
            if (!(g instanceof List<?> fields)) continue;
            String name = null;
            long lag = 0;
            for (int i = 0; i + 1 < fields.size(); i += 2) {
                String field = str(fields.get(i));
                if ("name".equals(field)) name = str(fields.get(i + 1));
                if ("lag".equals(field)) lag = parseLong(fields.get(i + 1));
            }
            if (StreamKeys.GROUP.equals(name)) return lag;
        }
        return 0;
    }

    private static String str(Object o) {
        return o instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(o);
    }

    private static long parseLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(str(o)); } catch (NumberFormatException e) { return 0; }
    }
}