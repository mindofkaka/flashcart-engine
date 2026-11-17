package com.flashcart.engine.exception;

public class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(Long id) {
        super("Produk %d sedang diperebutkan, silakan coba lagi".formatted(id));
    }
}