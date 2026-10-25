package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigurationTests {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PasswordConfiguration.class);

    @Test
    void applicationEncoderUsesBcryptCostTwelveAndChecksThePassword() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(PasswordEncoder.class);
                    var encoder = context.getBean(PasswordEncoder.class);
                    String raw = "A test password for MasterEnglish";
                    String encoded = encoder.encode(raw);

                    assertThat(encoded).matches("^\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}$");
                    assertThat(encoded).isNotEqualTo(raw);
                    assertThat(encoder.matches(raw, encoded)).isTrue();
                    assertThat(encoder.matches("A different test password", encoded)).isFalse();
                });
    }

    @Test
    void repeatedEncodingUsesIndependentSalts() {
        contextRunner.run(
                context -> {
                    var encoder = context.getBean(PasswordEncoder.class);
                    String raw = "A test password for MasterEnglish";
                    assertThat(encoder.encode(raw)).isNotEqualTo(encoder.encode(raw));
                });
    }

    @Test
    void newPasswordsUseUtf8ByteLimitWithoutSilentTruncation() {
        contextRunner.run(
                context -> {
                    var encoder = context.getBean(PasswordEncoder.class);
                    String atLimit = "ệ".repeat(24);
                    String overLimit = atLimit + "a";
                    assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(72);
                    assertThat(overLimit.getBytes(StandardCharsets.UTF_8)).hasSize(73);

                    String encoded = encoder.encode(atLimit);
                    assertThat(encoder.matches(atLimit, encoded)).isTrue();
                    assertThatThrownBy(() -> encoder.encode(overLimit))
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }
}
