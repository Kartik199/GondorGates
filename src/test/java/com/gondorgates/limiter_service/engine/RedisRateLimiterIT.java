package com.gondorgates.limiter_service.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;

@SpringBootTest
@Testcontainers
class RedisRateLimiterIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379); // Standard Redis port

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Test
    void testDistributedThrottling() {
        String userId = "distributed-user";

        // Step 1: Consume 10 tokens
        for (int i = 0; i < 10; i++) {
            StepVerifier.create(redisRateLimiter.isAllowed(userId))
                    .expectNextMatches(RateLimitDecision::allowed)
                    .verifyComplete();
        }

        // Step 2: 11th request must be rejected (proving state is persisted in Redis)
        StepVerifier.create(redisRateLimiter.isAllowed(userId))
                .expectNextMatches(decision -> !decision.allowed())
                .verifyComplete();
    }

    @Test
    void testRetryAfterCalculation() {
        String userId = "retry-user";

        // Drain the bucket
        for (int i = 0; i < 10; i++) { redisRateLimiter.isAllowed(userId).block(); }

        // The 11th request should tell us to wait ~1000ms (since rate is 1/sec)
        StepVerifier.create(redisRateLimiter.isAllowed(userId))
                .expectNextMatches(decision ->
                        !decision.allowed() &&
                                decision.retryAfter().toMillis() > 0 &&
                                decision.retryAfter().toMillis() <= 1000)
                .verifyComplete();
    }
}