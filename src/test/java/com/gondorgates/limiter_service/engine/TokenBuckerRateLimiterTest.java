package com.gondorgates.limiter_service.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        // 1. Initialize our "Time Machine" starting at a fixed point
        Instant startTime = Instant.parse("2026-03-15T10:00:00Z");
        testClock = new TestClock(startTime);

        // 2. Manually inject the clock into the limiter
        // This solves the "Bean not found" error because we aren't asking Spring to do it
        limiter = new TokenBucketRateLimiter(testClock);
    }

    @Test
    void testBurstAndThrottle() {
        String key = "user-123";

        // Burst: Capacity is 10. First 10 should pass.
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.isAllowed(key).block().allowed(), "Request " + i + " should be allowed");
        }

        // Throttle: 11th request must fail
        assertFalse(limiter.isAllowed(key).block().allowed(), "11th request should be throttled");
    }

    @Test
    void testRefillWithTimeTravel() {
        String key = "user-refill";

        // 1. Drain the bucket
        for (int i = 0; i < 10; i++) {
            limiter.isAllowed(key).block();
        }

        // 2. Verify it's empty
        assertFalse(limiter.isAllowed(key).block().allowed());

        // 3. Move the clock forward by 5 seconds (Refill rate is 1 token/sec)
        testClock.advanceBy(Duration.ofSeconds(5));

        // 4. Verify we can now make a request
        RateLimitDecision decision = limiter.isAllowed(key).block();
        assertTrue(decision.allowed(), "Should be allowed after 5 seconds of refill");
        assertEquals(4, decision.remainingTokens(), "Should have 4 tokens left (5 refilled - 1 consumed)");
    }

    /**
     * Helper Class: A mutable clock that allows us to simulate the passage of time
     * without using Thread.sleep().
     */
    private static class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advanceBy(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}