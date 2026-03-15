package com.gondorgates.limiter_service;

import com.gondorgates.limiter_service.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(RedisConfig.class) // Ensure our manual Redis beans are loaded
class LimiterServiceApplicationTests {

	@Test
	void contextLoads() {
		// This test simply verifies that the Spring context can start
	}
}