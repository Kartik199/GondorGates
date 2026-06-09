package com.gondorgates.limiter.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Rate-limit budget for a single dimension (GLOBAL, USER, IP, or API_KEY)")
public class DimensionPolicy {

    @NotNull(message = "dimension type must not be null")
    @Schema(description = "Which traffic dimension this budget applies to", example = "USER")
    private RateLimitDimension type;

    @Positive(message = "capacity must be greater than zero")
    @Schema(description = "Maximum token bucket size — burst ceiling", example = "5")
    private int capacity;

    @Positive(message = "refillRate must be greater than zero")
    @Schema(description = "Tokens added per second", example = "1")
    private int refillRate;

    public DimensionPolicy() {}

    public RateLimitDimension getType() { return type; }
    public void setType(RateLimitDimension type) { this.type = type; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRefillRate() { return refillRate; }
    public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
}
