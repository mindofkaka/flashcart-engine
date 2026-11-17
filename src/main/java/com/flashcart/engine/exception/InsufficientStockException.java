package com.flashcart.engine.exception;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long id, int requested) {
        super("Stok produk %d tidak cukup untuk jumlah %d".formatted(id, requested));
    }
}