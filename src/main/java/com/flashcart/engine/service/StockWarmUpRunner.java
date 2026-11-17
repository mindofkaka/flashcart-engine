package com.flashcart.engine.service;

import com.flashcart.engine.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component 
@RequiredArgsConstructor 
public class StockWarmUpRunner implements ApplicationRunner{

    private final ProductRepository repository;
    private final RedisInventoryService redisInventory;

    @Override 
    public void run(ApplicationArguments args) {
        repository.findAll().forEach(p->redisInventory.warmUpIfAbsent(p.getId(), p.getStock()));
    }
}
