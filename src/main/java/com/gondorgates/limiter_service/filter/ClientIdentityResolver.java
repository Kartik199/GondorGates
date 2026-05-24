package com.gondorgates.limiter_service.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;

@Component
public class ClientIdentityResolver {

    private static final String ANONYMOUS = "anonymous";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_USER_ID = "X-User-Id";

    public String resolve(ServerHttpRequest request) {
        String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (StringUtils.hasText(apiKey)) return apiKey;

        String userId = request.getHeaders().getFirst(HEADER_USER_ID);
        if (StringUtils.hasText(userId)) return userId;

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) return remoteAddress.getAddress().getHostAddress();

        return ANONYMOUS;
    }
}