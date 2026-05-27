package com.gondorgates.limiter_service.admin;

import com.gondorgates.limiter_service.policy.DimensionPolicy;
import com.gondorgates.limiter_service.policy.RateLimitDimension;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "gondorgates.admin-token=test-admin-token")
public class AdminPolicyControllerIT {

    private static final String TOKEN        = "test-admin-token";
    private static final String CUSTOM_PATH  = "/api/custom-probe";

    @Autowired WebTestClient webTestClient;
    @Autowired RedisPolicyStore policyStore;

    @BeforeEach
    void cleanup() {
        policyStore.delete(CUSTOM_PATH).block();
    }

    @Test
    void rejectsRequestWithoutToken() {
        webTestClient.get().uri("/admin/policies")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRequestWithWrongToken() {
        webTestClient.get().uri("/admin/policies")
                .header("X-Admin-Token", "wrong-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listPoliciesReturnsBothSections() {
        webTestClient.get().uri("/admin/policies")
                .header("X-Admin-Token", TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.yaml").isArray()
                .jsonPath("$.overrides").isArray();
    }

    @Test
    void customPolicyIsEnforcedAfterCreation() {
        // Create a tight policy — USER capacity 2 — on a path with no YAML entry
        webTestClient.post().uri("/admin/policies")
                .header("X-Admin-Token", TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.USER, 2, 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("saved");

        // First 2 requests must be allowed (filter passes them through → 404 from Spring, not 429)
        for (int i = 0; i < 2; i++) {
            webTestClient.get().uri(CUSTOM_PATH)
                    .header("X-User-Id", "probe-user")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        // 3rd request must be rate-limited
        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "probe-user")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("Retry-After");
    }

    @Test
    void deleteRestoresFallbackBehaviour() {
        // Create a 1-token policy then immediately delete it
        webTestClient.post().uri("/admin/policies")
                .header("X-Admin-Token", TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.USER, 1, 1))
                .exchange()
                .expectStatus().isOk();

        webTestClient.delete().uri("/admin/policies" + CUSTOM_PATH)
                .header("X-Admin-Token", TOKEN)
                .exchange()
                .expectStatus().isNoContent();

        // After deletion, the catch-all YAML policy applies (generous limits).
        // The request must be allowed through (404 from Spring = filter passed it).
        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "probe-user")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rejectsPolicyWithMissingPath() {
        RateLimitPolicy bad = new RateLimitPolicy();
        bad.setDimensions(List.of());

        webTestClient.post().uri("/admin/policies")
                .header("X-Admin-Token", TOKEN)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest();
    }

    private RateLimitPolicy buildPolicy(String path, RateLimitDimension type,
                                         int capacity, int refillRate) {
        DimensionPolicy dim = new DimensionPolicy();
        dim.setType(type);
        dim.setCapacity(capacity);
        dim.setRefillRate(refillRate);

        RateLimitPolicy policy = new RateLimitPolicy();
        policy.setPath(path);
        policy.setDimensions(List.of(dim));
        return policy;
    }
}
