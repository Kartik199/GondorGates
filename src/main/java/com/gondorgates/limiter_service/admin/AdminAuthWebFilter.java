package com.gondorgates.limiter_service.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Component
@Order(-99)
public class AdminAuthWebFilter implements WebFilter {

    private final String adminToken;

    public AdminAuthWebFilter(@Value("${gondorgates.admin-token:}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/admin")) {
            return chain.filter(exchange);
        }

        if (!StringUtils.hasText(adminToken)) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "{\"error\": \"Admin API is disabled — set GONDORGATES_ADMIN_TOKEN to enable it\"}");
        }

        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        String provided = (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : null;

        if (!constantTimeEquals(adminToken, provided)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "{\"error\": \"Invalid or missing Authorization: Bearer token\"}");
        }

        return chain.filter(exchange);
    }

    // Constant-time comparison prevents timing attacks where an attacker guesses
    // the token character by character by measuring response time differences.
    private boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}