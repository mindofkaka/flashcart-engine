package com.flashcart.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flashcart.rate-limit")
public record RateLimitProperties(boolean enabled, boolean failOpen, Rule ip, Rule user) {
    public record Rule(int capacity, double refillPerSecond) {}
}