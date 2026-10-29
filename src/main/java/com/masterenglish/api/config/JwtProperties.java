package com.masterenglish.api.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenDuration) {

    public static final String DEFAULT_SECRET =
            "masterenglish-test-secret-key-32-bytes-minimum-length-123456";
    public static final String DEFAULT_ISSUER = "masterenglish-api";
    public static final Duration DEFAULT_ACCESS_TOKEN_DURATION = Duration.ofMinutes(15);

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            secret = DEFAULT_SECRET;
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = DEFAULT_ISSUER;
        }
        if (accessTokenDuration == null
                || accessTokenDuration.isNegative()
                || accessTokenDuration.isZero()) {
            accessTokenDuration = DEFAULT_ACCESS_TOKEN_DURATION;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (256 bits) long.");
        }
    }

    public JwtProperties() {
        this(DEFAULT_SECRET, DEFAULT_ISSUER, DEFAULT_ACCESS_TOKEN_DURATION);
    }
}
