package com.flashcart.engine.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RedisController {
    private final StringRedisTemplate redis;

    public RedisController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/redis-test")
    public String test() {
        redis.opsForValue().set("ping", "pong");
        return redis.opsForValue().get("ping");
    }
}
