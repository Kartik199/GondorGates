package com.gondorgates.limiter_service.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientIdentityResolver {

    private static final String DEFAULT_USER = "anonymous";
    private static final String USER_ID_HEADER = "X-User-Id";

    public String resolve(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst(USER_ID_HEADER);

        // Return 'anonymous' if header is missing or empty to prevent NullPointer
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER;
    }
}