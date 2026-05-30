package com.gondorgates.limiter.engine;

import reactor.core.publisher.Mono;

public interface RateLimiter {
    Mono<RateLimitDecision> isAllowed(String key, int capacity, int refillRate);
}