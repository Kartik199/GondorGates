package com.gondorgates.limiter_service.engine;

import reactor.core.publisher.Mono;

public interface RateLimiter {
    Mono<RateLimitDecision> isAllowed(String key, int capacity, int refillRate);
}