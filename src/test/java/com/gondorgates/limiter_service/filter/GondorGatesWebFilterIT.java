package com.gondorgates.limiter_service.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class GondorGatesWebFilterIT {

    @Autowired
    private WebTestClient webClient;

    @Test
    void shouldEnforceRateLimitAcrossMultipleRequests() {
        String userId = "test";

        for (int i = 10; i > 0; i--) {
            webClient.get().uri("/api/ping")
                    .header("X-User-Id", userId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Remaining", String.valueOf(i - 1));
        }

        webClient.get().uri("/api/ping")
                .header("X-User-Id", userId)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error").isEqualTo("RATE_LIMIT_EXCEEDED");
    }
}