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
    // Keys mirror RateLimitKeyUtils.buildKey: rate_limit:{dimension}:{id}:{path}
    private static final String GLOBAL_KEY = "rate_limit:global:GLOBAL:/api/test";
    private static final String USER_KEY   = "rate_limit:user:" + TEST_USER + ":/api/test";
    private static final String ANON_KEY   = "rate_limit:user:anonymous:/api/test";

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
        redisTemplate.delete(GLOBAL_KEY, USER_KEY, ANON_KEY).block();
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

    @Test
    void anonymousUserFallsToAnonymousBucket() {
        // Requests without X-User-Id use the "anonymous" identity — they share one bucket
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/test")
                    .exchange()
                    .expectStatus().isOk();
        }
        // Anonymous bucket (capacity=10) is now exhausted
        webTestClient.get().uri("/api/test")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void rateLimitRemainingDecrementsOnEachRequest() {
        // /api/test: GLOBAL capacity=50, USER capacity=10. Min remaining after 1st = 9.
        webTestClient.get().uri("/api/test")
                .header("X-User-Id", TEST_USER)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "9");

        webTestClient.get().uri("/api/test")
                .header("X-User-Id", TEST_USER)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "8");
    }

    @Test
    void actuatorPathBypassesRateLimiter() {
        // Drain the USER bucket so any further /api/test requests would be blocked
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/test")
                    .header("X-User-Id", TEST_USER)
                    .exchange();
        }
        // /actuator/** must bypass the filter and remain accessible
        webTestClient.get().uri("/actuator/health")
                .header("X-User-Id", TEST_USER)
                .exchange()
                .expectStatus().isOk();
    }
}
