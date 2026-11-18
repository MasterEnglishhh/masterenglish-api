package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimitSubjects {
    public static final String EMAIL_SCOPE = "LOGIN_FAILURE_EMAIL";
    public static final String IP_SCOPE = "LOGIN_IP";
    public static final int EMAIL_LIMIT = 5;
    public static final int IP_LIMIT = 300;
    public static final int WINDOW_SECONDS = 900;
    public static final int BLOCK_SECONDS = 900;

    private final SecretKeySpec key;

    LoginRateLimitSubjects(
            JwtProperties jwtProperties,
            @Value("${app.security.rate-limit.secret:}") String rateLimitSecret) {
        String secret = rateLimitSecret.isBlank() ? jwtProperties.secret() : rateLimitSecret;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String emailHash(String email) {
        return email == null || email.isBlank()
                ? null
                : hash(email.trim().toLowerCase(Locale.ROOT));
    }

    public String ipHash(String ip) {
        return ip == null || ip.isBlank() ? null : hash(ip.trim());
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to compute rate-limit HMAC.", exception);
        }
    }
}
