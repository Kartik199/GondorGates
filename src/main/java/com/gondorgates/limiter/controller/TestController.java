package com.gondorgates.limiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TestController {

    @GetMapping("/api/ping")
    public Mono<String> ping() {
        return Mono.just("GondorGates: Request Allowed!");
    }
}