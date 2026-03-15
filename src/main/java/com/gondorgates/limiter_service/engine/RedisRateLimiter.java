package com.gondorgates.limiter_service.engine;

import com.gondorgates.limiter_service.util.RateLimitKeyUtils;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class RedisRateLimiter implements RateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> script;
    private final Clock clock;

    public RedisRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate, RedisScript<List> script, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.script = script;
        this.clock = clock;
    }

    @Override
    public Mono<RateLimitDecision> isAllowed(String identifier) {
        String key = RateLimitKeyUtils.buildKey("user", identifier, "default-api");

        List<String> keys = List.of(key);
        Object[] args = new Object[]{"10", "1", String.valueOf(clock.millis()), "1", "60"};

        return redisTemplate.execute(script, keys, List.of(args)).next()
                .map(result -> {
                    // Result format from Lua: {allowed, tokens, retry_after}
                    boolean allowed = ((Long) result.get(0)) == 1L;
                    long remaining = (Long) result.get(1);
                    long retryAfterMs = (Long) result.get(2);

                    return new RateLimitDecision(allowed, remaining, Duration.ofMillis(retryAfterMs));
                });
    }
}