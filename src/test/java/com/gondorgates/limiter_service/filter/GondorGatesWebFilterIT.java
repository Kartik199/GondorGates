package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({RedisConfig.class, GondorGatesWebFilterIT.TestController.class})
public class GondorGatesWebFilterIT {

    private static final String TEST_USER = "test-user";
    // Key format mirrors what GondorGatesWebFilter builds: clientId + ":" + policy.getPath()
    private static final String RATE_LIMIT_KEY = TEST_USER + ":/api/test";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @RestController
    public static class TestController {
        @GetMapping("/api/test")
        public String test() { return "OK"; }
    }

    @BeforeEach
    void resetRateLimitState() {
        redisTemplate.delete(RATE_LIMIT_KEY).block();
    }

    @Test
    void shouldAllowRequestsWithinCapacity() {
        // /api/test policy: capacity = 10
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/test")
                    .header("X-User-Id", TEST_USER)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists("X-RateLimit-Remaining");
        }
    }

    @Test
    void shouldBlockRequestsBeyondCapacity() {
        // Drain the 10-token bucket
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/test")
                    .header("X-User-Id", TEST_USER)
                    .exchange()
                    .expectStatus().isOk();
        }
        // Next requests must be rejected
        for (int i = 0; i < 5; i++) {
            webTestClient.get().uri("/api/test")
                    .header("X-User-Id", TEST_USER)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists("Retry-After");
        }
    }
}
