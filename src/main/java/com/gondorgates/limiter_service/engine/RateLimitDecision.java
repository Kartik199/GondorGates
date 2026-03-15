package com.gondorgates.limiter_service.engine;

import java.time.Duration;

/**
 * @param allowed         Whether the request can proceed
 * @param remainingTokens Tokens left in the bucket after this request
 * @param retryAfter      Waiting Time before the next available time
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, Duration retryAfter) {

}

