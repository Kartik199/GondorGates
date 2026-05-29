package com.gondorgates.limiter_service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisPolicyStore implements PolicyStore {

    private static final String KEY_PREFIX = "gondorgates:admin:policy:";
    private static final String INDEX_KEY  = "gondorgates:admin:paths";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, RateLimitPolicy> cache = new ConcurrentHashMap<>();

    public RedisPolicyStore(ReactiveStringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        Gauge.builder("gondor.admin.policies.active", cache, ConcurrentHashMap::size)
                .description("Number of active admin policy overrides stored in Redis")
                .register(meterRegistry);
    }

    @PostConstruct
    void loadFromRedis() {
        try {
            redis.opsForSet().members(INDEX_KEY)
                    .flatMap(path -> redis.opsForValue().get(KEY_PREFIX + path)
                            .flatMap(json -> Mono.fromCallable(() -> deserialize(json)))
                            .doOnNext(policy -> cache.put(policy.getPath(), policy))
                            .onErrorResume(e -> Mono.empty()))
                    .blockLast(Duration.ofSeconds(5));
        } catch (Exception e) {
            // Redis unavailable at startup — cache stays empty, YAML policies apply
        }
    }

    public RateLimitPolicy get(String path) {
        return cache.get(path);
    }

    public List<RateLimitPolicy> listAll() {
        return new ArrayList<>(cache.values());
    }

    public Mono<Void> save(RateLimitPolicy policy) {
        String json;
        try {
            json = objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return redis.opsForValue().set(KEY_PREFIX + policy.getPath(), json)
                .then(redis.opsForSet().add(INDEX_KEY, policy.getPath()).then())
                .doOnSuccess(v -> cache.put(policy.getPath(), policy));
    }

    public Mono<Void> delete(String path) {
        return redis.delete(KEY_PREFIX + path)
                .then(redis.opsForSet().remove(INDEX_KEY, (Object) path).then())
                .doOnSuccess(v -> cache.remove(path));
    }

    private RateLimitPolicy deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, RateLimitPolicy.class);
    }
}
