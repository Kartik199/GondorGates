package com.gondorgates.limiter_service.config;

import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "gondorgates")
public class GondorGatesProperties {
    private List<RateLimitPolicy> policies;

    public List<RateLimitPolicy> getPolicies() { return policies; }
    public void setPolicies(List<RateLimitPolicy> policies) { this.policies = policies; }
}