package com.flashcart.engine.config;

import com.flashcart.engine.idempotency.IdempotencyFilter;
import com.flashcart.engine.metrics.FlashCartMetrics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            StringRedisTemplate redis, IdempotencyProperties props, FlashCartMetrics metrics) {
        var reg = new FilterRegistrationBean<>(new IdempotencyFilter(redis, props, metrics));
        reg.addUrlPatterns("/api/v1/products/*");
        return reg;
    }
}