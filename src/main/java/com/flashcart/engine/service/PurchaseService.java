package com.flashcart.engine.service;

import com.flashcart.engine.metrics.FlashCartMetrics;
import com.flashcart.engine.stream.OrderCreatedEvent;
import com.flashcart.engine.stream.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final RedisInventoryService redisInventory;
    private final OrderEventPublisher publisher;
    private final FlashCartMetrics metrics;

    public UUID purchase(Long productId, int quantity) {
        redisInventory.decrementOrFail(productId, quantity);        // 1. fast-fail (409)
        UUID orderId = UUID.randomUUID();
        try {
            publisher.publish(new OrderCreatedEvent(orderId, productId, quantity)); // 2. XADD
            metrics.orderAccepted();  
        } catch (RuntimeException e) {
            redisInventory.restore(productId, quantity);            // XADD failed: undo step 1
            throw e;
        }
        return orderId;                                             // 3. return immediately
    }
}