package com.gondorgates.limiter;

import com.gondorgates.limiter.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(RedisConfig.class) // Ensure our manual Redis beans are loaded
class GondorGatesApplicationTests {

	@Test
	void contextLoads() {
		// This test simply verifies that the Spring context can start
	}
}