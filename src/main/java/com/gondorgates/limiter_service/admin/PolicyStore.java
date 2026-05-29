package com.gondorgates.limiter_service.admin;

import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import reactor.core.publisher.Mono;

import java.util.List;

public interface PolicyStore {
    RateLimitPolicy get(String path);
    List<RateLimitPolicy> listAll();
    Mono<Void> save(RateLimitPolicy policy);
    Mono<Void> delete(String path);
}
