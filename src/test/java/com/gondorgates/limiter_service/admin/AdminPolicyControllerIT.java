package com.gondorgates.limiter_service.admin;

import com.gondorgates.limiter_service.policy.DimensionPolicy;
import com.gondorgates.limiter_service.policy.RateLimitDimension;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "gondorgates.admin-token=test-admin-token")
public class AdminPolicyControllerIT {

    private static final String TOKEN       = "test-admin-token";
    private static final String CUSTOM_PATH = "/api/custom-probe";

    @Autowired WebTestClient webTestClient;
    @Autowired RedisPolicyStore policyStore;
    @Autowired ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanup() {
        policyStore.delete(CUSTOM_PATH).block();
        // Clean up token bucket keys so tests are independent of run order
        redisTemplate.delete(
                "rate_limit:global:GLOBAL:" + CUSTOM_PATH,
                "rate_limit:user:probe-user:" + CUSTOM_PATH,
                "rate_limit:user:user-alpha:" + CUSTOM_PATH,
                "rate_limit:user:user-beta:" + CUSTOM_PATH,
                "rate_limit:user:user-gamma:" + CUSTOM_PATH
        ).block();
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
                .header("Authorization", "Bearer wrong-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRequestWithLegacyXAdminTokenHeader() {
        webTestClient.get().uri("/admin/policies")
                .header("X-Admin-Token", TOKEN)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void listPoliciesReturnsBothSections() {
        webTestClient.get().uri("/admin/policies")
                .header("Authorization", "Bearer " + TOKEN)
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
                .header("Authorization", "Bearer " + TOKEN)
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
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.USER, 1, 1))
                .exchange()
                .expectStatus().isOk();

        webTestClient.delete().uri("/admin/policies" + CUSTOM_PATH)
                .header("Authorization", "Bearer " + TOKEN)
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
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsPolicyWithZeroCapacity() {
        DimensionPolicy dim = new DimensionPolicy();
        dim.setType(RateLimitDimension.USER);
        dim.setCapacity(0);
        dim.setRefillRate(1);

        RateLimitPolicy bad = new RateLimitPolicy();
        bad.setPath(CUSTOM_PATH);
        bad.setDimensions(List.of(dim));

        webTestClient.post().uri("/admin/policies")
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void upsertReplacesExistingPolicy() {
        // Create capacity=5, then immediately replace with capacity=1
        webTestClient.post().uri("/admin/policies")
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.USER, 5, 1))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/admin/policies")
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.USER, 1, 1))
                .exchange()
                .expectStatus().isOk();

        // Only 1 token available — first request allowed
        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "probe-user")
                .exchange()
                .expectStatus().isNotFound();

        // Bucket exhausted — second request blocked
        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "probe-user")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void globalDimensionBlocksAcrossAllUsers() {
        // GLOBAL capacity=2 — shared by every caller regardless of identity
        webTestClient.post().uri("/admin/policies")
                .header("Authorization", "Bearer " + TOKEN)
                .bodyValue(buildPolicy(CUSTOM_PATH, RateLimitDimension.GLOBAL, 2, 1))
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "user-alpha")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "user-beta")
                .exchange()
                .expectStatus().isNotFound();

        // Third request from a different user — GLOBAL bucket exhausted
        webTestClient.get().uri(CUSTOM_PATH)
                .header("X-User-Id", "user-gamma")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
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
