package com.gondorgates.limiter_service.config;

import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "gondorgates")
@Validated
public class GondorGatesProperties {
    @Valid
    private List<RateLimitPolicy> policies;

    public List<RateLimitPolicy> getPolicies() { return policies; }
    public void setPolicies(List<RateLimitPolicy> policies) { this.policies = policies; }
}