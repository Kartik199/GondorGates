package com.gondorgates.limiter_service.engine;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    @SuppressWarnings("rawtypes")
    public RedisRateLimiter(ReactiveStringRedisTemplate redisTemplate, RedisScript<List> script,
                             MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.script = script;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<RateLimitDecision> isAllowed(String key, int capacity, int refillRate) {
        long ttlSeconds = Math.max(60L, (long) capacity * 10 / refillRate);
        Timer.Sample sample = Timer.start(meterRegistry);

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
                    sample.stop(Timer.builder("gondor.redis.eval.duration")
                            .publishPercentileHistogram(true)
                            .register(meterRegistry));
                    boolean allowed = ((Long) results.get(0)) == 1L;
                    long remaining = (Long) results.get(1);
                    long retryAfterMs = (Long) results.get(2);
                    return new RateLimitDecision(allowed, remaining, Duration.ofMillis(retryAfterMs), capacity);
                })
                .onErrorResume(e -> {
                    meterRegistry.counter("gondor.redis.errors.total").increment();
                    log.error("CRITICAL: Redis Limiter failed. Reason: {}", e.getMessage());
                    return Mono.just(new RateLimitDecision(true, 0, Duration.ZERO, capacity));
                });
    }
}
