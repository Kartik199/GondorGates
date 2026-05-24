package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.engine.RateLimiter;
import com.gondorgates.limiter_service.policy.PolicyResolver;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-100)
public class GondorGatesWebFilter implements WebFilter {

    private final RateLimiter rateLimiter;
    private final ClientIdentityResolver identityResolver;
    private final PolicyResolver policyResolver;

    public GondorGatesWebFilter(RateLimiter rateLimiter, ClientIdentityResolver identityResolver, PolicyResolver policyResolver) {
        this.rateLimiter = rateLimiter;
        this.identityResolver = identityResolver;
        this.policyResolver = policyResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        RateLimitPolicy policy = policyResolver.resolve(path);

        if (policy == null) {
            return chain.filter(exchange);
        }

        String clientId = identityResolver.resolve(exchange.getRequest());
        String limitKey = clientId + ":" + policy.getPath();

        return rateLimiter.isAllowed(limitKey, policy.getCapacity(), policy.getRefillRate())
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        exchange.getResponse().beforeCommit(() -> {
                            exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(policy.getCapacity()));
                            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
                            return Mono.empty();
                        });
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(policy.getCapacity()));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
                    exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(decision.retryAfter().toSeconds()));
                    return handle429(exchange);
                });
    }

    private Mono<Void> handle429(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\": \"Too Many Requests\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }
}