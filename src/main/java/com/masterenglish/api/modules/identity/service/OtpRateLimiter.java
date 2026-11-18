package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.RateLimitExceededException;
import com.masterenglish.api.config.RegistrationProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OtpRateLimiter {
    private final OtpRateLimitStore store;
    private final SecretKeySpec key;

    public OtpRateLimiter(
            OtpRateLimitStore store,
            RegistrationProperties properties,
            @Value("${app.security.rate-limit.secret:}") String rateSecret) {
        this.store = store;
        String secret = rateSecret.isBlank() ? properties.otpSecret() : rateSecret;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public RateLimitExceededException reserveSend(String email, String ip, Instant now) {
        return limited(store.reserveSend(hash(email), hash(ip), now));
    }

    public RateLimitExceededException reserveVerification(String email, String ip, Instant now) {
        return reserveVerification(email, ip, "EMAIL_VERIFICATION", now);
    }

    public RateLimitExceededException reserveVerification(
            String email, String ip, String purpose, Instant now) {
        return limited(
                store.reserveVerification(verificationScope(purpose), hash(email), hash(ip), now));
    }

    public RateLimitExceededException checkVerificationBlocked(
            String email, String purpose, Instant now) {
        return limited(
                store.checkVerificationBlocked(verificationScope(purpose), hash(email), now));
    }

    public RateLimitExceededException wrongCode(String email, Instant now) {
        return wrongCode(email, "EMAIL_VERIFICATION", now);
    }

    public RateLimitExceededException wrongCode(String email, String purpose, Instant now) {
        return limited(store.wrongCode(verificationScope(purpose), hash(email), now));
    }

    private static String verificationScope(String purpose) {
        return switch (purpose) {
            case "EMAIL_VERIFICATION", "PASSWORD_RESET", "PASSWORD_CHANGE" ->
                    "OTP_VERIFY_" + purpose;
            default -> throw new IllegalArgumentException("Unsupported OTP purpose");
        };
    }

    private static RateLimitExceededException limited(long seconds) {
        if (seconds <= 0) return null;
        log.debug("OTP rate limit reached; retry after {} seconds", seconds);
        return new RateLimitExceededException(
                seconds, "Bạn đã thử quá nhiều lần. Vui lòng chờ trước khi thử lại.");
    }

    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize rate-limit HMAC.", exception);
        }
    }
}
