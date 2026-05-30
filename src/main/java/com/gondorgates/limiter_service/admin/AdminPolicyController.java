package com.gondorgates.limiter_service.admin;

import com.gondorgates.limiter_service.config.GondorGatesProperties;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import jakarta.validation.Valid;
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

    public AdminPolicyController(PolicyStore policyStore, GondorGatesProperties properties) {
        this.policyStore = policyStore;
        this.properties = properties;
    }

    @GetMapping("/policies")
    public Mono<ResponseEntity<Object>> listPolicies() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "yaml", properties.getPolicies(),
                "overrides", policyStore.listAll()
        )));
    }

    @PostMapping("/policies")
    public Mono<ResponseEntity<Object>> upsertPolicy(@Valid @RequestBody RateLimitPolicy policy) {
        return policyStore.save(policy)
                .thenReturn(ResponseEntity.ok()
                        .<Object>body(Map.of("path", policy.getPath(), "status", "saved")));
    }

    @DeleteMapping("/policies/**")
    public Mono<ResponseEntity<Object>> deletePolicy(ServerHttpRequest request) {
        String fullPath = request.getPath().value();
        String policyPath = fullPath.substring("/admin/policies".length());
        if (!StringUtils.hasText(policyPath)) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "path is required — e.g. DELETE /admin/policies/api/login")));
        }
        return policyStore.delete(policyPath)
                .thenReturn(ResponseEntity.<Object>noContent().build());
    }
}
