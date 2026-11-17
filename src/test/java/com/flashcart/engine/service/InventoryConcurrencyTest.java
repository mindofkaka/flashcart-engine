package com.flashcart.engine.service;

import com.flashcart.engine.entity.Product;
import com.flashcart.engine.repository.ProductRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InventoryConcurrencyTest {

    @Autowired InventoryService inventory;
    @Autowired ProductRepository repository;

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void stokTidakBolehNegatifDanTidakOversell(LockStrategy strategy) throws Exception {
        Product p = repository.save(new Product("Sepatu", null, new BigDecimal("100000"), 10));
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit((Callable<Void>) () -> {
                ready.countDown();
                start.await();                 // semua thread dilepas bersamaan
                try {
                    inventory.reserve(p.getId(), 1, strategy);
                    success.incrementAndGet();
                } catch (Exception ignored) { }
                return null;
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int finalStock = repository.findById(p.getId()).orElseThrow().getStock();
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
        assertThat(finalStock).isEqualTo(10 - success.get()); // tidak ada stok "hilang"
        if (strategy != LockStrategy.OPTIMISTIC) {
            assertThat(success.get()).isEqualTo(10);          // tepat 10 yang berhasil
        }
    }
}