package com.gondorgates.limiter.policy;

import com.gondorgates.limiter.admin.RedisPolicyStore;
import com.gondorgates.limiter.config.GondorGatesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyResolverTest {

    @Mock
    private RedisPolicyStore policyStore;

    private PolicyResolver resolver;

    @BeforeEach
    void setUp() {
        when(policyStore.get(any())).thenReturn(null);

        GondorGatesProperties props = new GondorGatesProperties();
        props.setPolicies(List.of(
                policy("/api/login"),
                policy("/api/orders"),
                policy("/api/orders/special"),
                policy("/")
        ));
        resolver = new PolicyResolver(props, policyStore);
    }

    // ── Exact matches ──────────────────────────────────────────────────────────

    @Test
    void exactMatchLogin() {
        assertThat(resolver.resolve("/api/login").getPath()).isEqualTo("/api/login");
    }

    @Test
    void exactMatchOrders() {
        assertThat(resolver.resolve("/api/orders").getPath()).isEqualTo("/api/orders");
    }

    // ── Sub-path (prefix + "/") matches ───────────────────────────────────────

    @Test
    void subPathMatchesOrders() {
        assertThat(resolver.resolve("/api/orders/123").getPath()).isEqualTo("/api/orders");
    }

    @Test
    void deepSubPathMatchesOrders() {
        assertThat(resolver.resolve("/api/orders/2024/items/42").getPath()).isEqualTo("/api/orders");
    }

    @Test
    void trailingSlashMatchesExactPolicy() {
        // /api/login/ starts with /api/login/ → matches /api/login
        assertThat(resolver.resolve("/api/login/").getPath()).isEqualTo("/api/login");
    }

    // ── Longest-prefix-wins ────────────────────────────────────────────────────

    @Test
    void longerPolicyWinsOverShorterPrefix() {
        // /api/orders/special is a registered policy — must win over /api/orders
        assertThat(resolver.resolve("/api/orders/special").getPath()).isEqualTo("/api/orders/special");
    }

    @Test
    void subPathOfLongerPolicyMatchesLongerPolicy() {
        assertThat(resolver.resolve("/api/orders/special/detail").getPath()).isEqualTo("/api/orders/special");
    }

    // ── The historical prefix-overlap bug ─────────────────────────────────────

    @Test
    void loginAttemptDoesNotMatchLoginPolicy() {
        // /api/loginattempt must NOT match /api/login (no slash separator)
        assertThat(resolver.resolve("/api/loginattempt").getPath()).isEqualTo("/");
    }

    @Test
    void ordersBulkDoesNotMatchOrdersPolicy() {
        assertThat(resolver.resolve("/api/ordersBulk").getPath()).isEqualTo("/");
    }

    // ── Case sensitivity ───────────────────────────────────────────────────────

    @Test
    void uppercasePathDoesNotMatchLowercasePolicy() {
        assertThat(resolver.resolve("/API/LOGIN").getPath()).isEqualTo("/");
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @Test
    void unknownPathFallsToCatchAll() {
        assertThat(resolver.resolve("/api/unknown").getPath()).isEqualTo("/");
    }

    @Test
    void rootPathMatchesCatchAll() {
        assertThat(resolver.resolve("/").getPath()).isEqualTo("/");
    }

    @Test
    void wellKnownPathFallsToCatchAll() {
        assertThat(resolver.resolve("/.well-known/health").getPath()).isEqualTo("/");
    }

    // ── Admin override takes precedence ───────────────────────────────────────

    @Test
    void adminOverrideTakesPrecedenceOverYaml() {
        RateLimitPolicy override = policy("/api/login");
        override.setPath("/api/login"); // same path, different object = override
        when(policyStore.get("/api/login")).thenReturn(override);

        assertThat(resolver.resolve("/api/login")).isSameAs(override);
    }

    // ── No policies ───────────────────────────────────────────────────────────

    @Test
    void returnsNullWhenNoPoliciesConfigured() {
        GondorGatesProperties empty = new GondorGatesProperties();
        empty.setPolicies(List.of());
        PolicyResolver emptyResolver = new PolicyResolver(empty, policyStore);

        assertThat(emptyResolver.resolve("/api/login")).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RateLimitPolicy policy(String path) {
        RateLimitPolicy p = new RateLimitPolicy();
        p.setPath(path);
        DimensionPolicy dim = new DimensionPolicy();
        dim.setType(RateLimitDimension.GLOBAL);
        dim.setCapacity(10);
        dim.setRefillRate(1);
        p.setDimensions(List.of(dim));
        return p;
    }
}
