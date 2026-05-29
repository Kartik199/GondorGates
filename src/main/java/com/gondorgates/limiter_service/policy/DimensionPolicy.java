package com.gondorgates.limiter_service.policy;

import jakarta.validation.constraints.Positive;

public class DimensionPolicy {
    private RateLimitDimension type;
    @Positive(message = "capacity must be greater than zero")
    private int capacity;
    @Positive(message = "refillRate must be greater than zero")
    private int refillRate;

    public DimensionPolicy() {}

    public RateLimitDimension getType() { return type; }
    public void setType(RateLimitDimension type) { this.type = type; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRefillRate() { return refillRate; }
    public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
}
