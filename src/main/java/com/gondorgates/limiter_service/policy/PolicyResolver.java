package com.gondorgates.limiter_service.policy;

import com.gondorgates.limiter_service.config.GondorGatesProperties;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PolicyResolver {
    private final List<RateLimitPolicy> policies;

    public PolicyResolver(GondorGatesProperties properties) {
        this.policies = properties.getPolicies() != null
                ? properties.getPolicies().stream()
                .sorted(Comparator.comparing(RateLimitPolicy::getPath, Comparator.comparingInt(String::length).reversed()))
                .collect(Collectors.toList())
                : List.of();

        System.out.println("GondorGates Engine: Loaded " + policies.size() + " policies.");
    }

    public RateLimitPolicy resolve(String path) {
        return policies.stream()
                .filter(p -> path.startsWith(p.getPath()))
                .findFirst()
                .orElse(null);
    }
}