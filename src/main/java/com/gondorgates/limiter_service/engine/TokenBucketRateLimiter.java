package com.gondorgates.limiter_service.engine;

import com.gondorgates.limiter_service.engine.model.TokenBucket;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    // Storage for our buckets (Key = UserID/IP)
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    // Default config for now (Epic 4 will make this dynamic)
    private final long capacity = 10;
    private final long refillRate = 1; // 1 token per second

    @Override
    public Mono<RateLimitDecision> isAllowed(String key) {
        return Mono.fromSupplier(() -> {
            TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillRate));

            refill(bucket);

            // LOCK-FREE ATOMIC OPERATION
            AtomicLong tokenCounter = bucket.getTokens();
            long currentTokens;
            long newTokens;

            do {
                currentTokens = tokenCounter.get();
                if (currentTokens < 1) {
                    return new RateLimitDecision(false, 0, Duration.ofSeconds(1 / refillRate));
                }
                newTokens = currentTokens - 1;
                // compareAndSet(expectedValue, newValue)
                // This returns true ONLY if currentTokens is still what we think it is.
            } while (!tokenCounter.compareAndSet(currentTokens, newTokens));

            return new RateLimitDecision(true, newTokens, Duration.ZERO);
        });
    }

    private void refill(TokenBucket bucket) {
        long now = System.currentTimeMillis();
        long lastRefill = bucket.getLastRefillTimestamp();

        if (now <= lastRefill) return;

        long deltaMillis = now - lastRefill;
        long tokensToAdd = (deltaMillis * bucket.getRefillRate()) / 1000;

        if (tokensToAdd > 0) {
            long newTokens = Math.min(bucket.getCapacity(), bucket.getTokens().get() + tokensToAdd);

            bucket.getTokens().set(newTokens);
            bucket.setLastRefillTimestamp(now);
        }
    }
}