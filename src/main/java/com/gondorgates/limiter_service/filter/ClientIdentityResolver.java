package com.gondorgates.limiter_service.filter;

import com.gondorgates.limiter_service.policy.RateLimitDimension;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;

@Component
public class ClientIdentityResolver {

    private static final String ANONYMOUS = "anonymous";
    private static final String UNKNOWN_IP = "unknown_ip";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_USER_ID = "X-User-Id";

    public String resolveForDimension(ServerHttpRequest request, RateLimitDimension dimension) {
        return switch (dimension) {
            case GLOBAL  -> "GLOBAL";
            case USER    -> {
                String userId = request.getHeaders().getFirst(HEADER_USER_ID);
                yield StringUtils.hasText(userId) ? userId : ANONYMOUS;
            }
            case IP      -> resolveIp(request);
            case API_KEY -> {
                String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
                yield StringUtils.hasText(apiKey) ? apiKey : ANONYMOUS;
            }
        };
    }

    private String resolveIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : UNKNOWN_IP;
    }
}