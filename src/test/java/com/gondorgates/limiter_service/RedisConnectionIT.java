package com.gondorgates.limiter_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier; // Add this import
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest
class RedisConnectionIT {

    @Autowired
    @Qualifier("reactiveRedisTemplate")
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Test
    void testRedisConnection() {
        Mono<Boolean> result = redisTemplate.opsForValue()
                .set("test-key", "GondorCallsForAid")
                .then(redisTemplate.opsForValue().get("test-key"))
                .map("GondorCallsForAid"::equals);

        StepVerifier.create(result)
                .expectNext(true)
                .verifyComplete();
    }
}