package com.gondorgates.limiter_service.engine.model;

import java.util.concurrent.atomic.AtomicLong;

public class TokenBucket {
    private final long capacity;
    private final AtomicLong tokens;
    private final long refillRate;
    private volatile long lastRefillTimestamp;

    public TokenBucket(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillTimestamp=System.currentTimeMillis();
    }

    // Standard Getters
    public long getCapacity() { return capacity; }
    public AtomicLong getTokens() { return tokens; }
    public long getRefillRate() { return refillRate; }
    public long getLastRefillTimestamp() { return lastRefillTimestamp; }

    // Setter for timestamp to be used in Story 1.3
    public void setLastRefillTimestamp(long lastRefillTimestamp) {
        this.lastRefillTimestamp = lastRefillTimestamp;
    }
}
