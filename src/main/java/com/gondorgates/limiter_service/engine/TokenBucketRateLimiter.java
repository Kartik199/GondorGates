package com.gondorgates.limiter_service.engine;

import com.gondorgates.limiter_service.engine.model.TokenBucket;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter implements RateLimiter {

    private final Clock clock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // Spring will inject the system clock here automatically
    public TokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<RateLimitDecision> isAllowed(String key) {
        return Mono.fromSupplier(() -> {
            long now = clock.millis();

            // Get or create bucket using the injected clock's time
            TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(10, 1, now));

            // Delegate all logic to the bucket itself
            return bucket.tryConsume(now);
        });
    }
}