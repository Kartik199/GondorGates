package com.gondorgates.limiter_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // New Import
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import java.time.Clock;

@SpringBootTest
class LimiterServiceApplicationTests {

	@MockitoBean // Replaces @MockBean
	private Clock clock;

	@MockitoBean // Replaces @MockBean
	private ReactiveRedisTemplate<String, String> redisTemplate;

	@Test
	void contextLoads() {
		// This will now pass using the modern Spring 3.4+ approach
	}
}