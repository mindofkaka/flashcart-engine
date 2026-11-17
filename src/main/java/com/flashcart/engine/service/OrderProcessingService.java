package com.flashcart.engine.service;

import com.flashcart.engine.entity.Order;
import com.flashcart.engine.entity.OrderStatus;
import com.flashcart.engine.repository.OrderRepository;
import com.flashcart.engine.repository.ProductRepository;
import com.flashcart.engine.stream.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderRepository orders;
    private final ProductRepository products;

    /** Empty Optional = this order was already processed (duplicate delivery). */
    @Transactional
    public Optional<OrderStatus> process(OrderCreatedEvent e) {
        if (orders.existsById(e.orderId())) {
            return Optional.empty();
        }
        int updated = products.decrementStockIfAvailable(e.productId(), e.quantity());
        OrderStatus status = updated == 1 ? OrderStatus.CONFIRMED : OrderStatus.REJECTED;
        orders.save(new Order(e.orderId(), e.productId(), e.quantity(), status));
        return Optional.of(status);   // stock decrement + order insert commit together
    }
}