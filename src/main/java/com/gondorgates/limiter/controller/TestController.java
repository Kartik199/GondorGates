package com.gondorgates.limiter.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Hidden
@RestController
public class TestController {

    @GetMapping("/api/ping")
    public Mono<String> ping() {
        return Mono.just("GondorGates: Request Allowed!");
    }
}