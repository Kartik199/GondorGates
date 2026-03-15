package com.gondorgates.limiter_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Clock;

@SpringBootTest
// This tells Spring: "Do NOT try to set up real Redis connections for this test"
@EnableAutoConfiguration(exclude = {
		RedisAutoConfiguration.class,
		RedisReactiveAutoConfiguration.class
})
class LimiterServiceApplicationTests {

	@MockitoBean
	private Clock clock;

	@MockitoBean
	private ReactiveRedisTemplate<String, String> redisTemplate;

	@MockitoBean
	private RedisScript<?> redisScript;

	@Test
	void contextLoads() {
		// This will finally pass because we've disabled the conflicting auto-config
	}
}