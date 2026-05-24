package com.gondorgates.limiter_service.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

@SpringBootTest
public class RedisRateLimiterIT {

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private String loginKey;
    private String orderKey;

    @BeforeEach
    void setUp() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        loginKey = "test-" + runId + ":/api/login";
        orderKey = "test-" + runId + ":/api/orders";
        redisTemplate.delete(loginKey, orderKey).block();
    }

    @Test
    void testDynamicRateLimitingAcrossDifferentPolicies() {
        int loginCap = 2;
        int loginRefill = 1;

        rateLimiter.isAllowed(loginKey, loginCap, loginRefill).block();
        rateLimiter.isAllowed(loginKey, loginCap, loginRefill).block();

        Mono<RateLimitDecision> loginResult = rateLimiter.isAllowed(loginKey, loginCap, loginRefill);
        StepVerifier.create(loginResult)
                .expectNextMatches(decision -> !decision.allowed())
                .verifyComplete();

        int orderCap = 10;
        int orderRefill = 2;

        Mono<RateLimitDecision> orderResult = rateLimiter.isAllowed(orderKey, orderCap, orderRefill);
        StepVerifier.create(orderResult)
                .expectNextMatches(decision -> decision.allowed() && decision.remainingTokens() == 9)
                .verifyComplete();
    }
}
