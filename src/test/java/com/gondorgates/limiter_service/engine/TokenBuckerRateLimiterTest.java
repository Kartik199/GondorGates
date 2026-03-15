package com.gondorgates.limiter_service.engine;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void testRefillWithClockInjection() {
        // 1. Setup a fixed starting point in time
        Instant start = Instant.parse("2026-03-15T10:00:00Z");

        // We use a simple helper to allow us to "move" time
        TestClock testClock = new TestClock(start);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(testClock);
        String key = "test-user";

        // 2. Drain the bucket (Capacity = 10)
        for (int i = 0; i < 10; i++) {
            limiter.isAllowed(key).block();
        }

        // 3. Verify it's locked
        RateLimitDecision blocked = limiter.isAllowed(key).block();
        assertFalse(blocked.allowed(), "Should be blocked after 10 requests");

        // 4. "Time Travel" — Advance the clock by 2 seconds
        testClock.advanceBy(Duration.ofSeconds(2));

        // 5. Verify refill (Refill rate is 1 token/sec, so we should have 2 tokens)
        RateLimitDecision refilled = limiter.isAllowed(key).block();
        assertTrue(refilled.allowed(), "Should be allowed after clock advancement");
        assertEquals(1, refilled.remainingTokens(), "Should have 1 token left after consuming 1 of the 2 refilled");
    }

    // Simple inner class to act as our "Time Machine"
    private static class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) { this.now = start; }
        void advanceBy(Duration duration) { this.now = this.now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}