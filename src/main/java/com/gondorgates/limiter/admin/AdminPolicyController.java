package com.gondorgates.limiter.admin;

import com.gondorgates.limiter.config.GondorGatesProperties;
import com.gondorgates.limiter.policy.RateLimitPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/admin")
@Tag(name = "Policies", description = "Manage runtime rate-limit policy overrides")
@SecurityRequirement(name = "bearerAuth")
public class AdminPolicyController {

    private final PolicyStore policyStore;
    private final GondorGatesProperties properties;

    public AdminPolicyController(PolicyStore policyStore, GondorGatesProperties properties) {
        this.policyStore = policyStore;
        this.properties = properties;
    }

    @GetMapping("/policies")
    @Operation(summary = "List all active policies",
               description = "Returns the YAML baseline policies and any live runtime overrides stored in Redis.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Policy list returned"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token"),
        @ApiResponse(responseCode = "503", description = "Admin API disabled — GONDORGATES_ADMIN_TOKEN not set")
    })
    public Mono<ResponseEntity<Object>> listPolicies() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "yaml", properties.getPolicies(),
                "overrides", policyStore.listAll()
        )));
    }

    @PostMapping("/policies")
    @Operation(summary = "Create or update a policy override",
               description = "Saves a runtime policy override to Redis. Takes effect on the next request — no restart required. " +
                             "An override on the same path replaces the previous one.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Policy saved"),
        @ApiResponse(responseCode = "400", description = "Validation failed — path blank, dimensions empty, capacity or refillRate <= 0"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token"),
        @ApiResponse(responseCode = "503", description = "Admin API disabled (GONDORGATES_ADMIN_TOKEN not set) or Redis unavailable")
    })
    public Mono<ResponseEntity<Object>> upsertPolicy(@Valid @RequestBody RateLimitPolicy policy) {
        return policyStore.save(policy)
                .thenReturn(ResponseEntity.ok()
                        .<Object>body(Map.of("path", policy.getPath(), "status", "saved")));
    }

    @DeleteMapping("/policies/{*path}")
    @Operation(summary = "Delete a policy override",
               description = "Removes a runtime Redis override for the given path. " +
                             "The YAML baseline policy (if any) takes effect immediately on the next request.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Override deleted", content = @Content),
        @ApiResponse(responseCode = "400", description = "Path segment missing from URL"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token"),
        @ApiResponse(responseCode = "503", description = "Admin API disabled (GONDORGATES_ADMIN_TOKEN not set) or Redis unavailable")
    })
    public Mono<ResponseEntity<Object>> deletePolicy(
            @Parameter(description = "Policy path to delete, e.g. /api/login", example = "/api/login")
            @PathVariable String path) {
        if (!path.startsWith("/") || path.equals("/")) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "path is required — e.g. DELETE /admin/policies/api/login")));
        }
        return policyStore.delete(path)
                .thenReturn(ResponseEntity.<Object>noContent().build());
    }
}
