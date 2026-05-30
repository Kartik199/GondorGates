package com.gondorgates.limiter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest
class RedisConnectionIT {

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

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