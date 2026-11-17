package com.flashcart.engine.stream;

import com.flashcart.engine.entity.OrderStatus;
import com.flashcart.engine.metrics.FlashCartMetrics;
import com.flashcart.engine.service.OrderProcessingService;
import com.flashcart.engine.service.RedisInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final OrderProcessingService processing;
    private final RedisInventoryService redisInventory;
    private final StringRedisTemplate redis;
    private final FlashCartMetrics metrics;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        handle(record);
    }

    /** Also called by PendingMessageRecovery for redelivered messages. */
    public void handle(MapRecord<String, String, String> record) {
        try {
            OrderCreatedEvent event = OrderCreatedEvent.fromFields(record.getValue());
            Optional<OrderStatus> result = processing.process(event);

            result.ifPresent(metrics::orderPersisted);

            if (result.isPresent() && result.get() == OrderStatus.REJECTED) {
                // Postgres said no even though Redis said yes (drift): give the unit back
                redisInventory.restore(event.productId(), event.quantity());
            }
            ack(record);                                   // only after the DB commit

        } catch (DataIntegrityViolationException dup) {
            ack(record);                                   // another worker inserted it first
        } catch (Exception e) {
            // NO ack: the message stays in the Pending Entries List and gets retried
            log.error("Failed to process {}, will be retried", record.getId(), e);
        }
    }

    private void ack(MapRecord<String, String, String> record) {
        redis.<String, String>opsForStream().acknowledge(StreamKeys.ORDERS, StreamKeys.GROUP, record.getId());
    }
}