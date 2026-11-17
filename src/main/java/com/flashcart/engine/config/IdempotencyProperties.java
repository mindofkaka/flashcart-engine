package com.flashcart.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "flashcart.idempotency")
public record IdempotencyProperties(boolean enabled, Duration processingTtl, Duration completedTtl) {}