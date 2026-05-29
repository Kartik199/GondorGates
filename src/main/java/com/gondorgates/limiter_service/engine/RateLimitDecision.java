package com.gondorgates.limiter_service.engine;

import java.time.Duration;

/**
 * @param allowed         Whether the request can proceed
 * @param remainingTokens Tokens left in the bucket after this request
 * @param retryAfter      Time to wait before retrying (zero when allowed)
 * @param capacity        Maximum token capacity of the evaluated bucket
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, Duration retryAfter, long capacity) {

}

