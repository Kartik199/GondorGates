package com.gondorgates.limiter_service.admin;

import com.gondorgates.limiter_service.config.GondorGatesProperties;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminPolicyController {

    private final PolicyStore policyStore;
    private final GondorGatesProperties properties;
    private final String adminToken;

    public AdminPolicyController(PolicyStore policyStore,
                                  GondorGatesProperties properties,
                                  @Value("${gondorgates.admin-token:}") String adminToken) {
        this.policyStore = policyStore;
        this.properties = properties;
        this.adminToken = adminToken;
    }

    @GetMapping("/policies")
    public Mono<ResponseEntity<Object>> listPolicies(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!isAuthorized(token)) return unauthorized();
        return Mono.just(ResponseEntity.ok(Map.of(
                "yaml", properties.getPolicies(),
                "overrides", policyStore.listAll()
        )));
    }

    @PostMapping("/policies")
    public Mono<ResponseEntity<Object>> upsertPolicy(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody RateLimitPolicy policy) {
        if (!isAuthorized(token)) return unauthorized();
        return policyStore.save(policy)
                .thenReturn(ResponseEntity.ok()
                        .<Object>body(Map.of("path", policy.getPath(), "status", "saved")));
    }

    @DeleteMapping("/policies/**")
    public Mono<ResponseEntity<Object>> deletePolicy(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            ServerHttpRequest request) {
        if (!isAuthorized(token)) return unauthorized();
        String fullPath = request.getPath().value();
        String policyPath = fullPath.substring("/admin/policies".length());
        if (!StringUtils.hasText(policyPath)) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "path is required — e.g. DELETE /admin/policies/api/login")));
        }
        return policyStore.delete(policyPath)
                .thenReturn(ResponseEntity.<Object>noContent().build());
    }

    private boolean isAuthorized(String token) {
        return StringUtils.hasText(adminToken) && adminToken.equals(token);
    }

    private Mono<ResponseEntity<Object>> unauthorized() {
        if (!StringUtils.hasText(adminToken)) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error",
                            "Admin API is disabled — set GONDORGATES_ADMIN_TOKEN env var to enable it")));
        }
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid or missing X-Admin-Token header")));
    }
}
