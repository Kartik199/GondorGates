package com.gondorgates.limiter_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.Clock;

@SpringBootTest
class LimiterServiceApplicationTests {

	// This "fakes" the clock bean so the ApplicationContext can start successfully
	@MockBean
	private Clock clock;

	@Test
	void contextLoads() {
		// This test will now pass if the app starts without errors
	}
}