package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.engine.RateLimitDecision;
import com.gondorgates.limiter_service.engine.RateLimiter;
import com.gondorgates.limiter_service.policy.PolicyResolver;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import com.gondorgates.limiter_service.util.RateLimitKeyUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

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

        if (policy == null || policy.getDimensions() == null || policy.getDimensions().isEmpty()) {
            return chain.filter(exchange);
        }

        return evaluateDimensions(exchange, policy)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        exchange.getResponse().beforeCommit(() -> {
                            exchange.getResponse().getHeaders()
                                    .set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
                            return Mono.empty();
                        });
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
                    exchange.getResponse().getHeaders()
                            .set("Retry-After", String.valueOf(decision.retryAfter().toSeconds()));
                    return handle429(exchange);
                });
    }

    private Mono<RateLimitDecision> evaluateDimensions(ServerWebExchange exchange, RateLimitPolicy policy) {
        return Flux.fromIterable(policy.getDimensions())
                .concatMap(dim -> {
                    String id  = identityResolver.resolveForDimension(exchange.getRequest(), dim.getType());
                    String key = RateLimitKeyUtils.buildKey(dim.getType().name().toLowerCase(), id, policy.getPath());
                    return rateLimiter.isAllowed(key, dim.getCapacity(), dim.getRefillRate());
                })
                // stop after the first denial, but include that element so we can return it
                .takeUntil(decision -> !decision.allowed())
                // keep the most restrictive: a denial always wins, otherwise pick the smallest remaining
                .reduce((acc, next) -> !next.allowed() ? next
                        : next.remainingTokens() < acc.remainingTokens() ? next : acc)
                .defaultIfEmpty(new RateLimitDecision(true, Long.MAX_VALUE, Duration.ZERO));
    }

    private Mono<Void> handle429(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\": \"Too Many Requests\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }
}
