package com.gondorgates.limiter_service;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class PrometheusMetricsIT {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void gondorMetricsAreRecordedOnRequest() {
        // Drive a request through the rate-limiting filter via the production ping endpoint
        webTestClient.get().uri("/api/ping")
                .header("X-User-Id", "metrics-probe-user")
                .exchange()
                .expectStatus().isOk();

        assertThat(meterRegistry.find("gondor.requests.total")
                .tag("outcome", "allowed").counter())
                .isNotNull();
        assertThat(meterRegistry.find("gondor.filter.duration").timer())
                .isNotNull();
        assertThat(meterRegistry.find("gondor.redis.eval.duration").timer())
                .isNotNull();
        assertThat(meterRegistry.find("gondor.bucket.remaining").gauge())
                .isNotNull();
    }
}
