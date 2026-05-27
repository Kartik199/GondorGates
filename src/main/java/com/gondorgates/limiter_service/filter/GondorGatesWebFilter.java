package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.engine.RateLimitDecision;
import com.gondorgates.limiter_service.engine.RateLimiter;
import com.gondorgates.limiter_service.policy.PolicyResolver;
import com.gondorgates.limiter_service.policy.RateLimitPolicy;
import com.gondorgates.limiter_service.proxy.BackendProxyHandler;
import com.gondorgates.limiter_service.util.RateLimitKeyUtils;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(-100)
public class GondorGatesWebFilter implements WebFilter {

    private final RateLimiter rateLimiter;
    private final ClientIdentityResolver identityResolver;
    private final PolicyResolver policyResolver;
    private final MeterRegistry meterRegistry;
    private final BackendProxyHandler backendProxyHandler;

    // Backing store for gondor.bucket.remaining gauges — one AtomicLong per (dimension:path) pair.
    // Gauges are registered once on first sight and updated on every subsequent request.
    private final ConcurrentHashMap<String, AtomicLong> bucketGauges = new ConcurrentHashMap<>();

    public GondorGatesWebFilter(RateLimiter rateLimiter, ClientIdentityResolver identityResolver,
                                 PolicyResolver policyResolver, MeterRegistry meterRegistry,
                                 BackendProxyHandler backendProxyHandler) {
        this.rateLimiter = rateLimiter;
        this.identityResolver = identityResolver;
        this.policyResolver = policyResolver;
        this.meterRegistry = meterRegistry;
        this.backendProxyHandler = backendProxyHandler;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.startsWith("/actuator") || path.startsWith("/admin")) {
            return chain.filter(exchange);
        }

        RateLimitPolicy policy = policyResolver.resolve(path);

        if (policy == null || policy.getDimensions() == null || policy.getDimensions().isEmpty()) {
            return chain.filter(exchange);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String policyPath = policy.getPath();

        return evaluateDimensions(exchange, policy)
                .flatMap(decision -> {
                    String outcome = decision.allowed() ? "allowed" : "denied";

                    sample.stop(Timer.builder("gondor.filter.duration")
                            .tag("path", policyPath)
                            .tag("outcome", outcome)
                            .publishPercentileHistogram(true)
                            .register(meterRegistry));

                    meterRegistry.counter("gondor.requests.total",
                            "path", policyPath,
                            "outcome", outcome)
                            .increment();

                    if (decision.allowed()) {
                        exchange.getResponse().beforeCommit(() -> {
                            exchange.getResponse().getHeaders()
                                    .set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
                            return Mono.empty();
                        });
                        return backendProxyHandler.isEnabled()
                                ? backendProxyHandler.proxy(exchange)
                                : chain.filter(exchange);
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
                    return rateLimiter.isAllowed(key, dim.getCapacity(), dim.getRefillRate())
                            .doOnNext(d -> recordBucketGauge(policy.getPath(), dim.getType().name().toLowerCase(), d.remainingTokens()));
                })
                .takeUntil(decision -> !decision.allowed())
                .reduce((acc, next) -> !next.allowed() ? next
                        : next.remainingTokens() < acc.remainingTokens() ? next : acc)
                .defaultIfEmpty(new RateLimitDecision(true, Long.MAX_VALUE, Duration.ZERO));
    }

    private void recordBucketGauge(String path, String dimension, long remaining) {
        String gaugeKey = dimension + ":" + path;
        bucketGauges.computeIfAbsent(gaugeKey, k -> {
            AtomicLong ref = new AtomicLong(remaining);
            Gauge.builder("gondor.bucket.remaining", ref, AtomicLong::get)
                    .tag("path", path)
                    .tag("dimension", dimension)
                    .register(meterRegistry);
            return ref;
        }).set(remaining);
    }

    private Mono<Void> handle429(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\": \"Too Many Requests\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }
}
