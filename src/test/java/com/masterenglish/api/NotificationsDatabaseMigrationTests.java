package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.time.TimeZones;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class NotificationsDatabaseMigrationTests {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private ReminderRuleRepository rules;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;

    @Test
    @DisplayName(
            "V11 tạo đủ bốn bảng thông báo và seed quy tắc REVIEW_DUE với khoảng cách 720 phút")
    void createsTablesAndSeedsReviewDueRule() {
        for (String table :
                List.of(
                        "notification_preferences",
                        "reminder_rules",
                        "notifications",
                        "notification_deliveries")) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table))
                    .isEqualTo(table);
        }
        var rule = rules.findByCode("REVIEW_DUE").orElseThrow();
        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.getMinimumGapMinutes()).isEqualTo(720);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_rules", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Mọi timezone canonical hỗ trợ bởi JVM đều tồn tại trong PostgreSQL pg_timezone_names")
    void allSupportedCanonicalZonesExistInPostgres() {
        List<String> pgZones =
                jdbc.queryForList("SELECT name FROM pg_timezone_names", String.class);
        assertThat(pgZones).containsAll(TimeZones.canonicalZones());
    }

    @Test
    @DisplayName("Tùy chọn mới mặc định bật in-app, tắt email, chưa đặt giờ nhắc")
    void preferenceDefaultsMatchBaseline() {
        UUID user = user();
        jdbc.update("INSERT INTO notification_preferences(user_id) VALUES (?)", user);
        var row =
                jdbc.queryForMap(
                        "SELECT in_app_enabled,email_enabled,daily_reminder_time,timezone FROM notification_preferences WHERE user_id=?",
                        user);
        assertThat(row.get("in_app_enabled")).isEqualTo(true);
        assertThat(row.get("email_enabled")).isEqualTo(false);
        assertThat(row.get("daily_reminder_time")).isNull();
        assertThat(row.get("timezone")).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test
    @DisplayName("Entity ánh xạ đúng schema và lưu đọc lại được cả bốn bảng")
    void entitiesRoundTrip() {
        UUID user = user();
        var preference = new NotificationPreference(user);
        preference.update(
                true,
                true,
                LocalTime.of(20, 0),
                "Asia/Ho_Chi_Minh",
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                NOW);
        preferences.saveAndFlush(preference);

        var rule = rules.findByCode("REVIEW_DUE").orElseThrow();
        var notification =
                notifications.saveAndFlush(
                        new Notification(
                                user,
                                rule.getId(),
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Bạn có thẻ cần ôn",
                                "Có 5 thẻ Flashcard đến hạn hôm nay.",
                                "/learn/review",
                                "REVIEW_DUE:" + user + ":2026-11-19",
                                NOW));
        deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(notification.getId(), NOW));
        var email =
                deliveries.saveAndFlush(
                        NotificationDelivery.emailPending(
                                notification.getId(), "learner@example.test", NOW));

        email.claim(UUID.randomUUID(), NOW.plusSeconds(60), NOW);
        deliveries.saveAndFlush(email);
        email.scheduleRetry("SMTP_UNAVAILABLE", NOW.plus(1, ChronoUnit.MINUTES), NOW);
        deliveries.saveAndFlush(email);
        email.claim(UUID.randomUUID(), NOW.plusSeconds(120), NOW.plusSeconds(60));
        deliveries.saveAndFlush(email);
        email.markSent(null, NOW.plusSeconds(61));
        deliveries.saveAndFlush(email);

        notification.markRead(NOW);
        notifications.saveAndFlush(notification);

        assertThat(preferences.findById(user).orElseThrow().getQuietHoursEnd())
                .isEqualTo(LocalTime.of(7, 0));
        var inApp =
                deliveries
                        .findByNotificationIdAndChannel(
                                notification.getId(), DeliveryChannel.IN_APP)
                        .orElseThrow();
        assertThat(inApp.getStatus()).isEqualTo(DeliveryStatus.SENT);
        var sent =
                deliveries
                        .findByNotificationIdAndChannel(notification.getId(), DeliveryChannel.EMAIL)
                        .orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo((short) 2);
        assertThat(notifications.findById(notification.getId()).orElseThrow().getReadAt())
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("Delivery hết lượt thử chuyển FAILED hợp lệ với ràng buộc trạng thái")
    void failedDeliverySatisfiesStatusChecks() {
        UUID notification = notification(user(), "REVIEW_DUE:failed");
        var email =
                deliveries.saveAndFlush(
                        NotificationDelivery.emailPending(notification, "x@example.test", NOW));
        for (int attempt = 0; attempt < 4; attempt++) {
            email.claim(UUID.randomUUID(), NOW.plusSeconds(60), NOW);
            deliveries.saveAndFlush(email);
            if (attempt < 3) {
                email.scheduleRetry("SMTP_UNAVAILABLE", NOW.plusSeconds(60), NOW);
                deliveries.saveAndFlush(email);
            }
        }
        email.markFailed("LEASE_EXPIRED", NOW);
        deliveries.saveAndFlush(email);
        assertThat(email.getAttemptCount()).isEqualTo((short) 4);
        assertThat(email.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Một người dùng không thể có hai thông báo cùng dedupe_key")
    void rejectsDuplicateDedupeKeyPerUser() {
        UUID user = user();
        notification(user, "REVIEW_DUE:dup");
        assertRejected(() -> notification(user, "REVIEW_DUE:dup"));
        notification(user(), "REVIEW_DUE:dup");
    }

    @Test
    @DisplayName("Quiet hours phải cùng null hoặc cùng có giá trị và hai mốc khác nhau")
    void rejectsInvalidQuietHours() {
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_preferences(user_id,quiet_hours_start) VALUES (?,'22:00')",
                                user()));
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_preferences(user_id,quiet_hours_start,quiet_hours_end) VALUES (?,'22:00','22:00')",
                                user()));
    }

    @Test
    @DisplayName("target_url chỉ nhận đường dẫn nội bộ")
    void rejectsExternalTargetUrl() {
        UUID user = user();
        for (String url : List.of("https://evil.test", "//evil.test")) {
            assertRejected(
                    () ->
                            jdbc.update(
                                    "INSERT INTO notifications(id,user_id,type,title,body,target_url,dedupe_key) VALUES (?,?,'SYSTEM','t','b',?,?)",
                                    UUID.randomUUID(),
                                    user,
                                    url,
                                    UUID.randomUUID().toString()));
        }
    }

    @Test
    @DisplayName(
            "Ràng buộc delivery: email cần địa chỉ, attempt_count tối đa 4, PROCESSING cần claim")
    void enforcesDeliveryChecks() {
        UUID notification = notification(user(), "REVIEW_DUE:checks");
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_deliveries(id,notification_id,channel) VALUES (?,?,'EMAIL')",
                                UUID.randomUUID(),
                                notification));
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_deliveries(id,notification_id,channel,recipient_address,attempt_count) VALUES (?,?,'EMAIL','a@example.test',5)",
                                UUID.randomUUID(),
                                notification));
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_deliveries(id,notification_id,channel,recipient_address,status) VALUES (?,?,'EMAIL','a@example.test','PROCESSING')",
                                UUID.randomUUID(),
                                notification));
        jdbc.update(
                "INSERT INTO notification_deliveries(id,notification_id,channel) VALUES (?,?,'IN_APP')",
                UUID.randomUUID(),
                notification);
        assertRejected(
                () ->
                        jdbc.update(
                                "INSERT INTO notification_deliveries(id,notification_id,channel) VALUES (?,?,'IN_APP')",
                                UUID.randomUUID(),
                                notification));
    }

    /** A savepoint keeps the surrounding test transaction usable after the rejected statement. */
    private void assertRejected(Runnable statement) {
        jdbc.execute("SAVEPOINT rejected_statement");
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("ROLLBACK TO SAVEPOINT rejected_statement");
    }

    private UUID notification(UUID user, String dedupeKey) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notifications(id,user_id,type,title,body,dedupe_key) VALUES (?,?,'REVIEW_DUE','t','b',?)",
                id,
                user,
                dedupeKey);
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Notify', 'Fixture', 'ACTIVE', NOW())",
                id,
                id + "@example.test");
        return id;
    }
}
