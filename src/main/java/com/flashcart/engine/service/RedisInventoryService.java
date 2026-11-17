package com.flashcart.engine.service;

import com.flashcart.engine.exception.InsufficientStockException;
import com.flashcart.engine.exception.StockNotWarmedUpException;
import com.flashcart.engine.metrics.FlashCartMetrics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service 
@RequiredArgsConstructor 
public class RedisInventoryService {
    
    private static final String KEY_PREFIX = "flashcart:stock:";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> decrementStockScript;
    private final RedisScript<Long> incrementStockScript;
    private final FlashCartMetrics metrics;

    private String key(Long productId) {
        return KEY_PREFIX + productId;
    }

    public boolean warmUpIfAbsent(Long productId, int stock) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(key(productId), String.valueOf(stock))
        );
    }

    public void warmUp(Long productId, int stock) {
        redis.opsForValue().set(key(productId), String.valueOf(stock));
    }

    public void decrementOrFail(Long productId, int quantity) {
        if(quantity <= 0) {
            throw new IllegalArgumentException("Quantity harus lebih dari 0");
        }

        Long result = redis.execute(
            decrementStockScript,
            List.of(key(productId)),
            String.valueOf(quantity)
        );

        if(result == null) {
            throw new IllegalStateException("Redis tidak mengembalikan hasil");
        }

        switch(result.intValue()) {
            case 1 -> {}
            case 0 -> { metrics.outOfStock(); throw new InsufficientStockException(productId, quantity); }
            case -1 -> throw new StockNotWarmedUpException(productId);
            default -> throw new IllegalStateException("Kode hasil Lua tak dikenal:" + result);
        }
    }

    public void restore(Long productId, int quantity) {
        if(Boolean.TRUE.equals(redis.hasKey(key(productId)))) {
            redis.opsForValue().increment(key(productId), quantity);
        }
    }

    public Long currentStock(Long productId) {
        String v = redis.opsForValue().get(key(productId));
        return v == null ? null : Long.parseLong(v);
    }

    public void increase(Long productId, int quantity, int dbStockAfter) {
        Long result = redis.execute(incrementStockScript, List.of(key(productId)), String.valueOf(quantity));
        if (result != null && result == -1) {
            warmUpIfAbsent(productId, dbStockAfter);
        }
    }
}
