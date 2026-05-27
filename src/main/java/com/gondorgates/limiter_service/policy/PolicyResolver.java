package com.gondorgates.limiter_service.policy;

import com.gondorgates.limiter_service.admin.RedisPolicyStore;
import com.gondorgates.limiter_service.config.GondorGatesProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PolicyResolver {

    private final List<RateLimitPolicy> yamlPolicies;
    private final RedisPolicyStore policyStore;

    public PolicyResolver(GondorGatesProperties properties, RedisPolicyStore policyStore) {
        this.policyStore = policyStore;
        this.yamlPolicies = properties.getPolicies() != null
                ? properties.getPolicies().stream()
                        .sorted(Comparator.comparing(RateLimitPolicy::getPath,
                                Comparator.comparingInt(String::length).reversed()))
                        .collect(Collectors.toList())
                : List.of();
        System.out.println("GondorGates Engine: Loaded " + yamlPolicies.size() + " YAML policies.");
    }

    public RateLimitPolicy resolve(String path) {
        // Admin overrides take precedence — exact match only
        RateLimitPolicy override = policyStore.get(path);
        if (override != null) return override;

        // Fall through to YAML longest-prefix match
        return yamlPolicies.stream()
                .filter(p -> {
                    String policyPath = p.getPath();
                    return policyPath.equals("/")
                            || path.equals(policyPath)
                            || path.startsWith(policyPath + "/");
                })
                .findFirst()
                .orElse(null);
    }
}
