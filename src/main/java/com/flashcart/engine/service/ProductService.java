package com.flashcart.engine.service;

import com.flashcart.engine.dto.CreateProductRequest;
import com.flashcart.engine.entity.Product;
import com.flashcart.engine.exception.ProductNotFoundException;
import com.flashcart.engine.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;
    private final RedisInventoryService redisInventory;

    @Transactional
    public Product create(CreateProductRequest r) {
        Product saved = repository.save(new Product(r.name(), r.description(), r.price(), r.stock()));
        redisInventory.warmUp(saved.getId(), saved.getStock());
        return saved;
    }

    @Transactional(readOnly = true)
    public Product get(Long id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }
}