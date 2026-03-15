package com.gondorgates.limiter_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<List> script() {
        // This ensures the Lua script is loaded into the Spring Context correctly
        ClassPathResource resource = new ClassPathResource("scripts/token_bucket.lua");
        return RedisScript.of(resource, List.class);
    }
}