package com.gondorgates.limiter_service.policy;

public class RateLimitPolicy {
    private String path;
    private int capacity;
    private int refillRate;

    public RateLimitPolicy() {}

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRefillRate() { return refillRate; }
    public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
}