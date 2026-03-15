package com.gondorgates.limiter_service.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
public class RedisRateLimiterIT {

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void testDynamicRateLimitingAcrossDifferentPolicies() {
        String loginKey = "user1:/api/login";
        String orderKey = "user1:/api/orders";

        // 1. Test Strict Policy (Login: 2 requests allowed)
        int loginCap = 2;
        int loginRefill = 1;

        // First two should pass
        rateLimiter.isAllowed(loginKey, loginCap, loginRefill).block();
        rateLimiter.isAllowed(loginKey, loginCap, loginRefill).block();

        // Third should be blocked
        Mono<RateLimitDecision> loginResult = rateLimiter.isAllowed(loginKey, loginCap, loginRefill);
        StepVerifier.create(loginResult).expectNextMatches(decision -> !decision.allowed()).verifyComplete();

        // 2. Test Generous Policy (Orders: 10 requests allowed)
        // Even though login is blocked, orders should still work!
        int orderCap = 10;
        int orderRefill = 2;

        Mono<RateLimitDecision> orderResult = rateLimiter.isAllowed(orderKey, orderCap, orderRefill);
        StepVerifier.create(orderResult).expectNextMatches(decision -> decision.allowed() && decision.remainingTokens() == 9).verifyComplete();
    }
}