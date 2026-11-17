package com.flashcart.engine.service;

import com.flashcart.engine.exception.*;
import com.flashcart.engine.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final int MAX_RETRIES = 5;

    private final ProductRepository repository;
    private final TransactionTemplate tx;

    // Tidak ada @Transactional di class ini sama sekali.
    public void reserve(Long productId, int quantity, LockStrategy strategy) {
        switch (strategy) {
            case ATOMIC -> tx.executeWithoutResult(s -> doAtomic(productId, quantity));
            case PESSIMISTIC -> tx.executeWithoutResult(s -> doPessimistic(productId, quantity));
            case OPTIMISTIC -> reserveOptimistic(productId, quantity);
        }
    }

    private void doAtomic(Long productId, int quantity) {
        int updated = repository.decrementStockIfAvailable(productId, quantity);
        if (updated == 0) {
            if (!repository.existsById(productId)) {
                throw new ProductNotFoundException(productId);
            }
            throw new InsufficientStockException(productId, quantity);
        }
    }

    private void doPessimistic(Long productId, int quantity) {
        var product = repository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        product.decreaseStock(quantity);
    }

    private void reserveOptimistic(Long productId, int quantity) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    var product = repository.findById(productId)
                            .orElseThrow(() -> new ProductNotFoundException(productId));
                    product.decreaseStock(quantity);
                });
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_RETRIES) throw new ConcurrentUpdateException(productId);
                sleepWithJitter(attempt);
            }
        }
    }

    private void sleepWithJitter(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 20L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}