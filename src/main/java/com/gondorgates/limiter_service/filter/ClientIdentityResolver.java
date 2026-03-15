package com.gondorgates.limiter_service.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIdentityResolver {

    public String resolve(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId != null) return userId;

        String apiKey = request.getHeaders().getFirst("X-API-Key");
        if (apiKey != null) return apiKey;

        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "anonymous";
    }
}