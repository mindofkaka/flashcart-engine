package com.flashcart.engine.repository;

import com.flashcart.engine.entity.Order;
import com.flashcart.engine.entity.OrderStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    long countByProductIdAndStatus(Long productId, OrderStatus status);
}