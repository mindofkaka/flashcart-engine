package com.flashcart.engine.stream;

import com.flashcart.engine.service.RedisInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingMessageRecovery {

    private static final Duration MIN_IDLE = Duration.ofSeconds(30);
    private static final long MAX_DELIVERIES = 5;

    private final StringRedisTemplate redis;
    private final OrderStreamConsumer consumer;
    private final RedisInventoryService redisInventory;
    private final String consumerName;

    @Scheduled(fixedDelay = 30_000)
    public void recover() {
        StreamOperations<String, String, String> ops = redis.opsForStream();

        PendingMessages pending = ops.pending(StreamKeys.ORDERS, StreamKeys.GROUP, Range.unbounded(), 100L);
        if (pending == null || pending.isEmpty()) return;

        Map<String, Long> deliveries = new HashMap<>();
        List<RecordId> stale = new ArrayList<>();
        for (PendingMessage m : pending) {
            if (m.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE) >= 0) {
                stale.add(m.getId());
                deliveries.put(m.getIdAsString(), m.getTotalDeliveryCount());
            }
        }
        if (stale.isEmpty()) return;

        // XCLAIM: take ownership of messages idle for 30s+ (crashed or failing workers)
        List<MapRecord<String, String, String>> claimed =
                ops.claim(StreamKeys.ORDERS, StreamKeys.GROUP, consumerName, MIN_IDLE,
                          stale.toArray(RecordId[]::new));

        for (MapRecord<String, String, String> record : claimed) {
            long count = deliveries.getOrDefault(record.getId().getValue(), 0L);
            if (count >= MAX_DELIVERIES) {
                deadLetter(ops, record);
            } else {
                consumer.handle(record);
            }
        }
    }

    private void deadLetter(StreamOperations<String, String, String> ops,
                            MapRecord<String, String, String> record) {
        log.error("Moving poison message {} to DLQ", record.getId());
        ops.add(StreamRecords.string(record.getValue()).withStreamKey(StreamKeys.ORDERS_DLQ));
        ops.acknowledge(StreamKeys.ORDERS, StreamKeys.GROUP, record.getId());
        try {
            OrderCreatedEvent e = OrderCreatedEvent.fromFields(record.getValue());
            redisInventory.restore(e.productId(), e.quantity());   // the order never happened
        } catch (RuntimeException ignored) { /* malformed payload: nothing to restore */ }
    }
}