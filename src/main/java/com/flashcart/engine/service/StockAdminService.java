package com.flashcart.engine.service;

import com.flashcart.engine.exception.ProductNotFoundException;
import com.flashcart.engine.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockAdminService {

    private final ProductRepository products;
    private final RedisInventoryService redisInventory;
    private final TransactionTemplate tx;

    public int addStock(Long productId, int quantity) {
        // 1. Postgres: committed BEFORE touching Redis
        Integer dbStock = tx.execute(status -> {
            if (products.incrementStock(productId, quantity) == 0) {
                throw new ProductNotFoundException(productId);
            }
            return products.findById(productId).orElseThrow().getStock();
        });

        // 2. Redis: atomic increment (or warm-up if key is missing)
        try {
            redisInventory.increase(productId, quantity, dbStock);
        } catch (RuntimeException e) {
            // DB is already correct; Redis is temporarily lower (under-sells, never oversells)
            log.error("Redis not updated for product {}; needs reconciliation", productId, e);
        }
        return dbStock;
    }
}