package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.config.NotificationProperties.Mail;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationPropertiesTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @Test
    @DisplayName(
            "Spring Boot bind được NotificationProperties khi không cấu hình mail hoặc có settings-path")
    void bindsViaSpringConfigurationProperties() {
        runner.run(
                context -> {
                    NotificationProperties props = context.getBean(NotificationProperties.class);
                    assertThat(props.defaultTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
                    assertThat(props.leaseSeconds()).isEqualTo(60);
                    assertThat(props.mail().enabled()).isFalse();
                    assertThat(props.mail().settingsPath()).isEmpty();
                    assertThat(props.mail().hasSettingsPath()).isFalse();
                });
        runner.withPropertyValues(
                        "notification.mail.enabled=true",
                        "notification.mail.from=noreply@example.test",
                        "notification.mail.web-base-url=https://app.example.test",
                        "notification.mail.settings-path=/learn/notifications/settings")
                .run(
                        context -> {
                            NotificationProperties props =
                                    context.getBean(NotificationProperties.class);
                            assertThat(props.mail().deliverable()).isTrue();
                            assertThat(props.mail().hasSettingsPath()).isTrue();
                            assertThat(props.mail().settingsPath())
                                    .isEqualTo("/learn/notifications/settings");
                        });
    }

    @Configuration
    @EnableConfigurationProperties(NotificationProperties.class)
    static class BindingConfig {}

    @Test
    @DisplayName(
            "Email nhắc chỉ được gửi khi bật mail và có cả MAIL_FROM lẫn NOTIFICATION_WEB_BASE_URL")
    void emailNeedsSenderAndWebAddress() {
        assertThat(
                        new Mail(true, "noreply@example.test", "https://app.example.test", "")
                                .deliverable())
                .isTrue();
        assertThat(new Mail(true, "noreply@example.test", "", "").deliverable()).isFalse();
        assertThat(new Mail(true, "noreply@example.test", "   ", "").deliverable()).isFalse();
        assertThat(new Mail(true, "", "https://app.example.test", "").deliverable()).isFalse();
        assertThat(
                        new Mail(false, "noreply@example.test", "https://app.example.test", "")
                                .deliverable())
                .isFalse();
    }

    @Test
    @DisplayName("Địa chỉ web phải là URL http(s) tuyệt đối; dấu / cuối được bỏ")
    void validatesWebAddress() {
        assertThat(new Mail(true, "a@example.test", "https://app.example.test/", "").webBaseUrl())
                .isEqualTo("https://app.example.test");
        assertThat(new Mail(true, "a@example.test", "http://localhost:3000/app", "").webBaseUrl())
                .isEqualTo("http://localhost:3000/app");
        for (String invalid : List.of("app.example.test", "/learn", "ftp://x", "https://a b")) {
            assertThatThrownBy(() -> new Mail(true, "a@example.test", invalid, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(
                        new Mail(
                                        true,
                                        "a@example.test",
                                        "https://app.example.test",
                                        " /learn/notifications/settings ")
                                .settingsPath())
                .isEqualTo("/learn/notifications/settings");
        for (String invalidPath : List.of("learn/settings", "//evil.test/settings")) {
            assertThatThrownBy(
                            () ->
                                    new Mail(
                                            true,
                                            "a@example.test",
                                            "https://app.example.test",
                                            invalidPath))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Cấu hình retry ngoài giới hạn CHECK attempt_count làm app dừng khi khởi động")
    void rejectsRetryOutsideAttemptLimit() {
        var backoff = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
        var mail = new Mail(false, "", "", "");
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 4, 60, backoff, 200, 20, mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Mars/Base", false, 3, 60, backoff, 200, 20, mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 60, backoff, 200, 20, mail)
                                .maxAttempts())
                .isEqualTo(4);
        assertThat(
                        new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 0, 60, List.of(), 200, 20, mail)
                                .maxAttempts())
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh",
                                        false,
                                        1,
                                        60,
                                        List.of(Duration.ofMinutes(-1)),
                                        200,
                                        20,
                                        mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh",
                                        false,
                                        1,
                                        60,
                                        List.of(Duration.ZERO),
                                        200,
                                        20,
                                        mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh",
                                        false,
                                        3,
                                        60,
                                        List.of(Duration.ofMinutes(1)),
                                        200,
                                        20,
                                        mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 0, backoff, 200, 20, mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 14, backoff, 200, 20, mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 15, backoff, 200, 20, mail)
                                .leaseSeconds())
                .isEqualTo(15);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 60, backoff, 0, 20, mail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new NotificationProperties(
                                        "Asia/Ho_Chi_Minh", false, 3, 60, backoff, 200, 0, mail))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
