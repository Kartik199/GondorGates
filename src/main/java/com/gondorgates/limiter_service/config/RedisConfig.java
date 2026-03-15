package com.gondorgates.limiter_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory factory) {
        // We use String serializers for both keys and values for better visibility in Redis CLI
        RedisSerializationContext<String, String> context = RedisSerializationContext.<String, String>newSerializationContext(StringRedisSerializer.UTF_8).key(StringRedisSerializer.UTF_8).value(StringRedisSerializer.UTF_8).hashKey(StringRedisSerializer.UTF_8).hashValue(StringRedisSerializer.UTF_8).build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public RedisScript<List> rateLimitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}