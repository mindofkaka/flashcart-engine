package com.flashcart.engine.config;

import com.flashcart.engine.stream.OrderStreamConsumer;
import com.flashcart.engine.stream.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Slf4j
@Configuration
@EnableScheduling
public class RedisStreamConfig {

    @Bean
    public String consumerName() {
        return "worker-" + ProcessHandle.current().pid();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> orderListenerContainer(
            RedisConnectionFactory factory,
            StringRedisTemplate redis,
            OrderStreamConsumer consumer,
            String consumerName) {

        createGroupIfAbsent(redis);   // must happen before the container starts reading

        var options = StreamMessageListenerContainerOptions.builder()
                .batchSize(10)
                .pollTimeout(Duration.ofSeconds(2))
                .errorHandler(t -> log.error("Stream container error", t))
                .build();

        var container = StreamMessageListenerContainer.create(factory, options);

        // receive(...) = manual ack. receiveAutoAck(...) would ack before processing.
        container.receive(
                Consumer.from(StreamKeys.GROUP, consumerName),
                StreamOffset.create(StreamKeys.ORDERS, ReadOffset.lastConsumed()), // ">"
                consumer);

        return container;
    }

    private void createGroupIfAbsent(StringRedisTemplate redis) {
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
                c.streamCommands().xGroupCreate(
                        StreamKeys.ORDERS.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        StreamKeys.GROUP,
                        ReadOffset.from("0"),
                        true);          // MKSTREAM: create the stream if it doesn't exist yet
                return null;
            });
            log.info("Created consumer group {}", StreamKeys.GROUP);
        } catch (Exception e) {
            if (isBusyGroup(e)) {
                log.info("Consumer group {} already exists", StreamKeys.GROUP);
            } else {
                throw e;
            }
        }
    }

    private boolean isBusyGroup(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) return true;
        }
        return false;
    }
}