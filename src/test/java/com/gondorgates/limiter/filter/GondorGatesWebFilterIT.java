package com.gondorgates.limiter.filter;

import com.gondorgates.limiter.config.RedisConfig;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

    // ── 4.2 Anonymous bucket isolation ────────────────────────────────────────

    @Test
    void anonymousRequestsShareASingleBudget() {
        // Two callers with no X-User-Id both draw from the same "anonymous" bucket.
        // /api/test USER capacity = 10. After 10 headerless requests the budget is
        // exhausted — the 11th is denied regardless of which "client" sends it.
        for (int i = 0; i < 10; i++) {
            webTestClient.get().uri("/api/test").exchange().expectStatus().isOk();
        }
        // A completely separate request (different call site, same anonymous identity)
        // must be denied — it shares the same bucket, not a fresh one.
        webTestClient.get().uri("/api/test")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After");
    }

    // ── 4.5 Concurrent stress — atomic correctness under load ─────────────────

    @Test
    void exactlyCapacityRequestsAllowedUnderConcurrentLoad() throws InterruptedException {
        // /api/test USER dimension: capacity = 10.
        // Fire 50 threads simultaneously against the same user bucket.
        // The Lua script must be atomic — no double-spend, no under-grant.
        String userId = "stress-" + UUID.randomUUID();
        int total = 50;
        int expectedAllowed = 10; // USER capacity for /api/test

        AtomicInteger allowedCount = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(total);

        for (int i = 0; i < total; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    boolean ok = webTestClient.get().uri("/api/test")
                            .header("X-User-Id", userId)
                            .exchange()
                            .returnResult(Void.class)
                            .getStatus()
                            .is2xxSuccessful();
                    if (ok) allowedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await();   // all threads standing by
        start.countDown(); // fire
        done.await(30, TimeUnit.SECONDS);

        assertThat(allowedCount.get()).isEqualTo(expectedAllowed);
    }

    // ── Other ─────────────────────────────────────────────────────────────────

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
