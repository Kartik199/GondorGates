package com.gondorgates.limiter_service.engine.model;

import com.gondorgates.limiter_service.engine.RateLimitDecision;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Solidified TokenBucket.
 * Logic is encapsulated here to make the transition to Lua seamless.
 */
public class TokenBucket {
    private final long capacity;
    private final long refillRate;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTimestamp;

    public TokenBucket(long capacity, long refillRate, long now) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillTimestamp = new AtomicLong(now);
    }

    /**
     * The atomic "Check-Refill-Act" mutation.
     * This exact logic will be mirrored in our Epic 2 Lua script.
     */
    public RateLimitDecision tryConsume(long now) {
        long currentTokens;
        long nextTokens;
        long lastRefill;

        do {
            lastRefill = lastRefillTimestamp.get();
            currentTokens = tokens.get();

            // 1. Calculate Refill (Lazy)
            long delta = Math.max(0, now - lastRefill);
            long refill = (delta * refillRate) / 1000;
            long toppedUp = Math.min(capacity, currentTokens + refill);

            // 2. Evaluation
            if (toppedUp < 1) {
                // Calculate wait time until 1 token is available
                long msToWait = Math.max(0, 1000 / refillRate);
                return new RateLimitDecision(false, toppedUp, Duration.ofMillis(msToWait));
            }

            nextTokens = toppedUp - 1;

            // 3. Attempt Atomic Update of tokens
        } while (!tokens.compareAndSet(currentTokens, nextTokens));

        // Update the timestamp after a successful consumption
        lastRefillTimestamp.set(now);
        return new RateLimitDecision(true, nextTokens, Duration.ZERO);
    }
}