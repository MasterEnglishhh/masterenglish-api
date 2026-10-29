package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthSecretsConfigurationTests {
    @Test
    void jwtHasNoProductionDefaultKey() {
        assertThatThrownBy(() -> new JwtProperties(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("too-short", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        String key = "a".repeat(64);
        assertThat(new JwtProperties(key, "issuer", Duration.ofMinutes(15)).toString())
                .doesNotContain(key);
    }

    @Test
    void otpRequiresAnIndependentSecretAndValidSender() {
        assertThatThrownBy(() -> new RegistrationProperties("", false, "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTP_HMAC_SECRET");
        assertThatThrownBy(() -> new RegistrationProperties("b".repeat(64), true, "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAIL_FROM");
    }
}
