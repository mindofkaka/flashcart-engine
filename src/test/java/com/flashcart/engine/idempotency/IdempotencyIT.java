package com.flashcart.engine.idempotency;

import com.flashcart.engine.dto.CreateProductRequest;
import com.flashcart.engine.service.ProductService;
import com.flashcart.engine.service.RedisInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIT {

    @LocalServerPort int port;
    @Autowired ProductService productService;
    @Autowired RedisInventoryService redisInventory;

    private final HttpClient http = HttpClient.newHttpClient();
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    private Long newProduct(int stock) {
        return productService.create(
                new CreateProductRequest("Idem Shoe", null, new BigDecimal("100000"), stock)).getId();
    }

    private HttpResponse<String> purchase(Long productId, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/products/" + productId + "/purchase"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}"));
        if (key != null) b.header("Idempotency-Key", key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String orderId(HttpResponse<String> r) {
        Matcher m = ORDER_ID.matcher(r.body());
        assertThat(m.find()).as("body has orderId: " + r.body()).isTrue();
        return m.group(1);
    }

    @Test
    void sequentialRetryReplaysSameOrder_andStockDropsOnce() throws Exception {
        Long id = newProduct(10);
        String key = UUID.randomUUID().toString();

        var first = purchase(id, key);
        var second = purchase(id, key);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(orderId(second)).isEqualTo(orderId(first));
        assertThat(redisInventory.currentStock(id)).isEqualTo(9);   // not 8
    }

    @Test
    void simultaneousIdenticalRequests_createExactlyOneOrder() throws Exception {
        Long id = newProduct(10);
        String key = UUID.randomUUID().toString();
        int n = 5;

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return purchase(id, key);
            }));
        }
        ready.await();
        start.countDown();

        Set<String> orderIds = new HashSet<>();
        int created = 0;
        for (var f : futures) {
            var r = f.get(30, TimeUnit.SECONDS);
            // each loser is either "in progress" (409) or a replay (202)
            assertThat(r.statusCode()).isIn(202, 409);
            if (r.statusCode() == 202) { created++; orderIds.add(orderId(r)); }
        }
        pool.shutdown();

        assertThat(created).isGreaterThanOrEqualTo(1);
        assertThat(orderIds).hasSize(1);                         // one single order id
        assertThat(redisInventory.currentStock(id)).isEqualTo(9); // stock reduced once
    }

    @Test
    void missingOrInvalidKeyIsRejected() throws Exception {
        Long id = newProduct(10);
        assertThat(purchase(id, null).statusCode()).isEqualTo(400);
        assertThat(purchase(id, "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(redisInventory.currentStock(id)).isEqualTo(10);
    }

    @Test
    void sameKeyOnDifferentProductIsRejected() throws Exception {
        Long a = newProduct(10), b = newProduct(10);
        String key = UUID.randomUUID().toString();

        assertThat(purchase(a, key).statusCode()).isEqualTo(202);
        assertThat(purchase(b, key).statusCode()).isEqualTo(422);
        assertThat(redisInventory.currentStock(b)).isEqualTo(10);
    }
}