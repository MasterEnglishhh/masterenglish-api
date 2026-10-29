package com.masterenglish.api.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.registration")
public record RegistrationProperties(
        String otpSecret, boolean mailEnabled, String from, boolean breachCheckEnabled) {
    public RegistrationProperties {
        if (otpSecret == null || otpSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "Set OTP_HMAC_SECRET to at least 32 random bytes in the backend environment.");
        }
        if (mailEnabled && (from == null || !from.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            throw new IllegalArgumentException(
                    "Set MAIL_FROM to a valid sender address when MAIL_ENABLED=true.");
        }
    }

    @Override
    public String toString() {
        return "RegistrationProperties[secrets redacted, mailEnabled=" + mailEnabled + "]";
    }
}
