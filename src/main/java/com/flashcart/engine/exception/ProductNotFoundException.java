package com.flashcart.engine.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Long id) {
        super("Produk dengan id %d tidak ditemukan".formatted(id));
    }
}