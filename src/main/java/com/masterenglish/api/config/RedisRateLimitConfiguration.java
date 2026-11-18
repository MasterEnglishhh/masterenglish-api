package com.masterenglish.api.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.security.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedisClient authRateLimitRedisClient(
            @Value("${app.security.rate-limit.redis-url}") String url,
            @Value("${app.security.rate-limit.redis-timeout-ms}") long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Auth rate-limit Redis timeout must be positive.");
        }
        RedisURI uri;
        try {
            uri = RedisURI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid auth rate-limit Redis URL.");
        }
        Duration timeout = Duration.ofMillis(timeoutMillis);
        uri.setTimeout(timeout);
        RedisClient client = RedisClient.create(uri);
        client.setOptions(
                ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .timeoutOptions(TimeoutOptions.enabled())
                        .build());
        return client;
    }
}
