package com.gondorgates.limiter_service.proxy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

@Component
public class BackendProxyHandler {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    private final WebClient webClient;
    private final boolean enabled;

    public BackendProxyHandler(WebClient.Builder webClientBuilder,
                                @Value("${gondorgates.backend-url:}") String backendUrl) {
        this.enabled = !backendUrl.isBlank();
        this.webClient = enabled
                ? webClientBuilder.baseUrl(backendUrl).build()
                : webClientBuilder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        var response = exchange.getResponse();

        HttpHeaders forwardedHeaders = new HttpHeaders();
        request.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                forwardedHeaders.addAll(name, values);
            }
        });

        return webClient
                .method(request.getMethod())
                .uri(uriBuilder -> uriBuilder
                        .path(request.getURI().getRawPath())
                        .query(request.getURI().getRawQuery())
                        .build())
                .headers(h -> h.addAll(forwardedHeaders))
                .body(BodyInserters.fromDataBuffers(request.getBody()))
                .exchangeToMono(clientResponse -> {
                    response.setStatusCode(clientResponse.statusCode());
                    clientResponse.headers().asHttpHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                            response.getHeaders().addAll(name, values);
                        }
                    });
                    return response.writeWith(clientResponse.bodyToFlux(DataBuffer.class));
                });
    }
}
