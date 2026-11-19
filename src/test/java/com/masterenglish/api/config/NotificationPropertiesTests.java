package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.config.NotificationProperties.Mail;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTests {

    @Test
    @DisplayName(
            "Email nhắc chỉ được gửi khi bật mail và có cả MAIL_FROM lẫn NOTIFICATION_WEB_BASE_URL")
    void emailNeedsSenderAndWebAddress() {
        assertThat(new Mail(true, "noreply@example.test", "https://app.example.test").deliverable())
                .isTrue();
        assertThat(new Mail(true, "noreply@example.test", "").deliverable()).isFalse();
        assertThat(new Mail(true, "noreply@example.test", "   ").deliverable()).isFalse();
        assertThat(new Mail(true, "", "https://app.example.test").deliverable()).isFalse();
        assertThat(
                        new Mail(false, "noreply@example.test", "https://app.example.test")
                                .deliverable())
                .isFalse();
    }

    @Test
    @DisplayName("Địa chỉ web phải là URL http(s) tuyệt đối; dấu / cuối được bỏ")
    void validatesWebAddress() {
        assertThat(new Mail(true, "a@example.test", "https://app.example.test/").webBaseUrl())
                .isEqualTo("https://app.example.test");
        assertThat(new Mail(true, "a@example.test", "http://localhost:3000/app").webBaseUrl())
                .isEqualTo("http://localhost:3000/app");
        for (String invalid : List.of("app.example.test", "/learn", "ftp://x", "https://a b")) {
            assertThatThrownBy(() -> new Mail(true, "a@example.test", invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Cấu hình retry ngoài giới hạn CHECK attempt_count làm app dừng khi khởi động")
    void rejectsRetryOutsideAttemptLimit() {
        var backoff = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
        var mail = new Mail(false, "", "");
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
    }
}
