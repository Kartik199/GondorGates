package com.gondorgates.limiter.policy;

import com.gondorgates.limiter.admin.PolicyStore;
import com.gondorgates.limiter.config.GondorGatesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(PolicyResolver.class);

    private final List<RateLimitPolicy> yamlPolicies;
    private final PolicyStore policyStore;

    public PolicyResolver(GondorGatesProperties properties, PolicyStore policyStore) {
        this.policyStore = policyStore;
        this.yamlPolicies = properties.getPolicies() != null
                ? properties.getPolicies().stream()
                        .sorted(Comparator.comparing(RateLimitPolicy::getPath,
                                Comparator.comparingInt(String::length).reversed()))
                        .collect(Collectors.toList())
                : List.of();
        log.info("GondorGates Engine: Loaded {} YAML policies.", yamlPolicies.size());
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
