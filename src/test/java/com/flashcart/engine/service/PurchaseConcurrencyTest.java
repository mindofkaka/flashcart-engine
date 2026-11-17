package com.flashcart.engine.service;

import com.flashcart.engine.dto.CreateProductRequest;
import com.flashcart.engine.entity.OrderStatus;
import com.flashcart.engine.exception.InsufficientStockException;
import com.flashcart.engine.repository.OrderRepository;
import com.flashcart.engine.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class PurchaseConcurrencyTest {

    @Autowired ProductService productService;
    @Autowired PurchaseService purchaseService;
    @Autowired RedisInventoryService redisInventory;
    @Autowired ProductRepository products;
    @Autowired OrderRepository orders;

    @Test
    void exactlyStockManyOrdersConfirmed_andBothStoresEndAtZero() throws Exception {
        // create() also warms up Redis
        Long id = productService.create(
                new CreateProductRequest("Load Test Shoe", null, new BigDecimal("100000"), 10)).getId();

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    purchaseService.purchase(id, 1);
                    accepted.incrementAndGet();
                } catch (InsufficientStockException e) {
                    soldOut.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).as("unexpected exceptions").isEmpty();
        assertThat(accepted.get()).isEqualTo(10);   // Redis Lua let exactly 10 through
        assertThat(soldOut.get()).isEqualTo(90);    // the rest fast-failed

        // The worker is asynchronous: wait for it to catch up
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(orders.countByProductIdAndStatus(id, OrderStatus.CONFIRMED)).isEqualTo(10));

        assertThat(orders.countByProductIdAndStatus(id, OrderStatus.REJECTED)).isZero();
        assertThat(products.findById(id).orElseThrow().getStock()).isZero();
        assertThat(redisInventory.currentStock(id)).isZero();
    }
}