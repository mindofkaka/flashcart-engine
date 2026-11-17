package com.flashcart.engine.stream;

public final class StreamKeys {
    public static final String ORDERS = "flashcart:events:orders";
    public static final String ORDERS_DLQ = "flashcart:events:orders:dlq";
    public static final String GROUP = "order-workers";
    private StreamKeys() {}
}