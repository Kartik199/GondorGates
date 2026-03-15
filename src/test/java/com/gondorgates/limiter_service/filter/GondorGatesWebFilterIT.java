package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(RedisConfig.class)
public class GondorGatesWebFilterIT {

    @Autowired
    private WebTestClient webTestClient;

    @RestController
    public static class TestController {
        @GetMapping("/api/test")
        public String test() { return "OK"; }
    }

    @Test
    void shouldEnforceRateLimit() {
        // Run 15 times for a 10-capacity limit
        for (int i = 0; i < 15; i++) {
            int finalI = i;
            webTestClient.get().uri("/api/test")
                    .header("X-User-Id", "test-user")
                    .exchange()
                    .expectBody(String.class).consumeWith(res -> {
                        System.out.println("Request " + finalI + " Status: " + res.getStatus());
                    });
        }
    }
}