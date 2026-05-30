package com.gondorgates.limiter.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    @Mock
    private RedisScript<List> script;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisRateLimiter(redisTemplate, script, new SimpleMeterRegistry());
    }

    // Casts below resolve the compile-time overload ambiguity between
    // execute(RedisScript, List<K>, Object...) and execute(RedisScript, List<K>, List<?>).

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void failsOpenWhenRedisThrowsException() {
        doReturn(Flux.error(new RuntimeException("Redis connection refused")))
                .when(redisTemplate).execute((RedisScript<List>) any(), any(java.util.List.class), any(java.util.List.class));

        RateLimitDecision decision = rateLimiter.isAllowed("key", 10, 1).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void failsOpenWhenRedisReturnsEmptyFlux() {
        // Empty Flux → .next() = Mono.empty() → map never runs → empty Mono returned.
        // The caller's defaultIfEmpty guard handles this as fail-open.
        doReturn(Flux.empty())
                .when(redisTemplate).execute((RedisScript<List>) any(), any(java.util.List.class), any(java.util.List.class));

        rateLimiter.isAllowed("key", 10, 1).blockOptional();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void failsOpenWhenRedisReturnsMalformedResult() {
        // Empty list causes IndexOutOfBoundsException inside the .map() lambda.
        // onErrorResume catches it and returns a fail-open decision.
        doReturn(Flux.just(java.util.List.of()))
                .when(redisTemplate).execute((RedisScript<List>) any(), any(java.util.List.class), any(java.util.List.class));

        RateLimitDecision decision = rateLimiter.isAllowed("key", 10, 1).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void returnsCorrectDecisionOnSuccess() {
        doReturn(Flux.just(java.util.List.of(1L, 4L, 0L)))
                .when(redisTemplate).execute((RedisScript<List>) any(), any(java.util.List.class), any(java.util.List.class));

        RateLimitDecision decision = rateLimiter.isAllowed("key", 5, 1).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(4L);
        assertThat(decision.capacity()).isEqualTo(5L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void returnsCorrectDecisionOnDenial() {
        doReturn(Flux.just(java.util.List.of(0L, 0L, 500L)))
                .when(redisTemplate).execute((RedisScript<List>) any(), any(java.util.List.class), any(java.util.List.class));

        RateLimitDecision decision = rateLimiter.isAllowed("key", 5, 1).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remainingTokens()).isEqualTo(0L);
        assertThat(decision.retryAfter().toMillis()).isEqualTo(500L);
    }
}
