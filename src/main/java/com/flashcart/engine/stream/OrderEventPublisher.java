package com.flashcart.engine.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final StringRedisTemplate redis;

    /** XADD flashcart:events:orders * orderId ... productId ... quantity ... */
    public RecordId publish(OrderCreatedEvent event) {
        RecordId id = redis.<String, String>opsForStream()
                .add(StreamRecords.string(event.toFields()).withStreamKey(StreamKeys.ORDERS));
        if (id == null) {
            throw new IllegalStateException("XADD returned no record id");
        }
        return id;
    }
}