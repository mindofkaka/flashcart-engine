package com.flashcart.engine.exception;

public class StockNotWarmedUpException extends RuntimeException{
    public StockNotWarmedUpException(Long id) {
        super("Stok produk %d belum tersedia di cache".formatted(id));
    }
}
