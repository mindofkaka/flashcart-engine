package com.flashcart.engine.stream;

import java.util.Map;
import java.util.UUID;

public record OrderCreatedEvent(UUID orderId, Long productId, int quantity) {

    public Map<String, String> toFields() {
        return Map.of(
                "orderId", orderId.toString(),
                "productId", productId.toString(),
                "quantity", String.valueOf(quantity));
    }

    public static OrderCreatedEvent fromFields(Map<String, String> f) {
        return new OrderCreatedEvent(
                UUID.fromString(f.get("orderId")),
                Long.valueOf(f.get("productId")),
                Integer.parseInt(f.get("quantity")));
    }
}