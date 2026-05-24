package com.gondorgates.limiter_service.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private final ReactiveStringRedisTemplate redisTemplate;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RedisRateLimiter(ReactiveStringRedisTemplate redisTemplate, RedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    @Override
    public Mono<RateLimitDecision> isAllowed(String key, int capacity, int refillRate) {
        long ttlSeconds = Math.max(60L, (long) capacity * 10 / refillRate);
        return redisTemplate.execute(
                        script,
                        List.of(key),
                        List.of(
                                String.valueOf(capacity),
                                String.valueOf(refillRate),
                                String.valueOf(Instant.now().toEpochMilli()),
                                "1",
                                String.valueOf(ttlSeconds)
                        )
                )
                .next()
                .map(results -> {
                    // Lua script returns [allowed (0/1), remaining_tokens, retry_after_ms]
                    boolean allowed = ((Long) results.get(0)) == 1L;
                    long remaining = (Long) results.get(1);
                    long retryAfterMs = (Long) results.get(2);

                    return new RateLimitDecision(allowed, remaining, Duration.ofMillis(retryAfterMs));
                })
                .onErrorResume(e -> {
                    log.error("CRITICAL: Redis Limiter failed. Reason: {}", e.getMessage());
                    // Fail open: allow the request if Redis is dead
                    return Mono.just(new RateLimitDecision(true, 0, Duration.ZERO));
                });
    }
}