package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.engine.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // Standard SLF4J Logger - Works on every JDK version without extra config
    private static final Logger log = LoggerFactory.getLogger(GondorGatesWebFilter.class);

    private final RateLimiter rateLimiter;
    private final ClientIdentityResolver identityResolver;

    public GondorGatesWebFilter(RateLimiter rateLimiter, ClientIdentityResolver identityResolver) {
        this.rateLimiter = rateLimiter;
        this.identityResolver = identityResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startTime = System.currentTimeMillis();

        String clientId = identityResolver.resolve(exchange.getRequest());
        String path = exchange.getRequest().getPath().value();
        String limitKey = String.format("%s:%s", clientId, path);

        return rateLimiter.isAllowed(limitKey)
                .flatMap(decision -> {
                    long duration = System.currentTimeMillis() - startTime;

                    log.info("GondorGates Check: Key={}, Allowed={}, Remaining={}, Latency={}ms",
                            limitKey, decision.allowed(), decision.remainingTokens(), duration);

                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Reset", String.valueOf(decision.retryAfter().toSeconds()));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Limit", "10");

                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    } else {
                        log.warn("Rate Limit Exceeded for Key: {}. Blocking request.", limitKey);
                        return handleRejectedRequest(exchange, decision.retryAfter().toSeconds());
                    }
                });
    }

    private Mono<Void> handleRejectedRequest(ServerWebExchange exchange, long retryAfter) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));

        String body = String.format("{\"error\": \"RATE_LIMIT_EXCEEDED\", \"retryAfter\": %d}", retryAfter);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }
}