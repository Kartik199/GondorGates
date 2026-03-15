package com.gondorgates.limiter_service.util;

public class RateLimitKeyUtils {
    private static final String PREFIX = "rate_limit";

    public static String buildKey(String dimension, String id, String endpoint) {
        // Example: rate_limit:user:kartik:/api/orders
        return String.join(":", PREFIX, dimension, id, endpoint);
    }
}