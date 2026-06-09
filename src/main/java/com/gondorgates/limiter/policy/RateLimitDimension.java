package com.gondorgates.limiter.policy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Traffic dimension to rate-limit by: " +
        "GLOBAL (shared across all callers), " +
        "USER (per X-User-Id header), " +
        "IP (per remote address), " +
        "API_KEY (per X-API-Key header)")
public enum RateLimitDimension {
    GLOBAL, USER, IP, API_KEY
}
