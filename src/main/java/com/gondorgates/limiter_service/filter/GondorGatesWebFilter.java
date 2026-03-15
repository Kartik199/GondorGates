package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.engine.RateLimiter;
import com.gondorgates.limiter_service.policy.PolicyResolver;
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

        return Mono.justOrEmpty(policyResolver.resolve(path))
                .flatMap(policy -> {
                    String clientId = identityResolver.resolve(exchange.getRequest());
                    String limitKey = clientId + ":" + policy.getPath();

                    return rateLimiter.isAllowed(limitKey, policy.getCapacity(), policy.getRefillRate())
                            .flatMap(decision -> {
                                if (decision.allowed()) {
                                    return chain.filter(exchange);
                                } else {
                                    return handle429(exchange);
                                }
                            });
                })
                .switchIfEmpty(chain.filter(exchange).then(Mono.empty()));
    }

    private Mono<Void> handle429(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\": \"Too Many Requests\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }
}