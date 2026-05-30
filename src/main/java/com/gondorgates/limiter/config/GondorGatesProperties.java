package com.gondorgates.limiter.config;

import com.gondorgates.limiter.policy.RateLimitPolicy;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "gondorgates")
@Validated
public class GondorGatesProperties {
    @Valid
    private List<RateLimitPolicy> policies;

    public List<RateLimitPolicy> getPolicies() { return policies; }
    public void setPolicies(List<RateLimitPolicy> policies) { this.policies = policies; }
}