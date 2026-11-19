package com.masterenglish.api.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(
        properties = {
            "notification.mail.enabled=true",
            "notification.mail.from=reminders@example.test",
            "notification.mail.web-base-url=http://localhost:3000/"
        })
@Transactional
class ReminderPlannerIntegrationTests {

    /** 20:00 in Asia/Ho_Chi_Minh, 13:00 in Europe/London, 08:00 in America/New_York. */
    private static final Instant NOW = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired private ReminderPlanner planner;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @MockitoBean private LearningEvents learning;

    @BeforeEach
    void everyLearnerHasSevenDueCards() {
        when(learning.countDueCards(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Set<UUID> ids = invocation.getArgument(0);
                            return ids.stream()
                                    .collect(Collectors.toMap(Function.identity(), id -> 7L));
                        });
    }

    @Test
    @DisplayName("Tạo nhắc REVIEW_DUE với nội dung có số thẻ, IN_APP đã gửi và EMAIL chờ gửi")
    void createsReminderForBothChannels() {
        UUID user = user("ACTIVE");
        preference(user, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isEqualTo(1);

        var row =
                jdbc.queryForMap(
                        "SELECT id,type,visibility,title,body,target_url,dedupe_key,reminder_rule_id FROM notifications WHERE user_id=?",
                        user);
        assertThat(row.get("type")).isEqualTo("REVIEW_DUE");
        assertThat(row.get("visibility")).isEqualTo("USER");
        assertThat(row.get("title")).isEqualTo("Bạn có 7 thẻ cần ôn hôm nay");
        assertThat((String) row.get("body")).startsWith("7 thẻ Flashcard đã đến hạn");
        assertThat(row.get("target_url")).isEqualTo("/learn/review");
        assertThat(row.get("dedupe_key")).isEqualTo("REVIEW_DUE:" + user + ":2026-11-19");
        assertThat(row.get("reminder_rule_id")).isNotNull();
        UUID id = (UUID) row.get("id");
        var inApp = deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.IN_APP);
        assertThat(inApp).get().extracting(d -> d.getStatus()).isEqualTo(DeliveryStatus.SENT);
        var email =
                deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.EMAIL).orElseThrow();
        assertThat(email.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(email.getRecipientAddress()).isEqualTo(user + "@example.test");
    }

    @Test
    @DisplayName("Chạy lặp trong cùng ngày không tạo thông báo trùng")
    void repeatedRunsDoNotDuplicate() {
        UUID user = user("ACTIVE");
        preference(user, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isEqualTo(1);
        assertThat(planner.planReviewDueReminders(NOW)).isZero();
        assertThat(planner.planReviewDueReminders(NOW.plusSeconds(900))).isZero();
        assertThat(count(user)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.user_id=?",
                                Integer.class,
                                user))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Tôn trọng múi giờ và giờ nhắc của từng người học")
    void respectsTimezoneAndReminderTime() {
        UUID notYet = user("ACTIVE");
        preference(notYet, true, false, "20:30", "Asia/Ho_Chi_Minh", null, null);
        UUID london = user("ACTIVE");
        preference(london, true, false, "13:00", "Europe/London", null, null);
        UUID newYork = user("ACTIVE");
        preference(newYork, true, false, "07:45", "America/New_York", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isEqualTo(2);
        assertThat(count(notYet)).isZero();
        assertThat(count(london)).isEqualTo(1);
        assertThat(dedupeKey(newYork)).isEqualTo("REVIEW_DUE:" + newYork + ":2026-11-19");
    }

    @Test
    @DisplayName("Không nhắc trong quiet hours vắt qua nửa đêm, nhắc ngay khi hết quiet hours")
    void respectsQuietHoursAcrossMidnight() {
        UUID user = user("ACTIVE");
        preference(user, true, false, "06:00", "Asia/Tokyo", "21:30", "07:00");

        // 06:30 in Tokyo on 20/11: past the reminder time but still inside quiet hours.
        assertThat(planner.planReviewDueReminders(Instant.parse("2026-11-19T21:30:00Z"))).isZero();
        // 07:00 in Tokyo: quiet hours end, the reminder for 20/11 is created.
        assertThat(planner.planReviewDueReminders(Instant.parse("2026-11-19T22:00:00Z")))
                .isEqualTo(1);
        assertThat(dedupeKey(user)).isEqualTo("REVIEW_DUE:" + user + ":2026-11-20");
    }

    @Test
    @DisplayName("Không nhắc khi không có thẻ đến hạn")
    void skipsLearnersWithoutDueCards() {
        doReturn(Map.of()).when(learning).countDueCards(any(), any());
        UUID user = user("ACTIVE");
        preference(user, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isZero();
        assertThat(count(user)).isZero();
    }

    @Test
    @DisplayName("Không nhắc tài khoản không ACTIVE")
    void skipsInactiveAccounts() {
        UUID user = user("DISABLED");
        preference(user, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isZero();
        assertThat(count(user)).isZero();
    }

    @Test
    @DisplayName("Tắt in-app thì chỉ có delivery EMAIL và thông báo không vào feed")
    void emailOnlyReminderStaysOutOfFeed() {
        UUID user = user("ACTIVE");
        preference(user, false, true, "20:00", "Asia/Ho_Chi_Minh", null, null);

        assertThat(planner.planReviewDueReminders(NOW)).isEqualTo(1);
        UUID id =
                UUID.fromString(
                        jdbc.queryForObject(
                                "SELECT id::text FROM notifications WHERE user_id=?",
                                String.class,
                                user));
        assertThat(deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.IN_APP)).isEmpty();
        assertThat(deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.EMAIL))
                .isPresent();
        assertThat(notifications.findFeed(user, PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(notifications.countUnreadInFeed(user)).isZero();
    }

    @Test
    @DisplayName("Tắt email thì không tạo delivery EMAIL")
    void inAppOnlyWhenEmailOff() {
        UUID user = user("ACTIVE");
        preference(user, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);

        planner.planReviewDueReminders(NOW);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.user_id=? AND d.channel='EMAIL'",
                                Integer.class,
                                user))
                .isZero();
    }

    @Test
    @DisplayName("Không nhắc khi lần nhắc trước còn trong minimum_gap 720 phút")
    void respectsMinimumGap() {
        UUID user = user("ACTIVE");
        preference(user, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);
        jdbc.update(
                "INSERT INTO notifications(id,user_id,reminder_rule_id,type,title,body,dedupe_key,created_at) "
                        + "SELECT ?,?,id,'REVIEW_DUE','t','b','REVIEW_DUE:'||?||':other',? FROM reminder_rules WHERE code='REVIEW_DUE'",
                UUID.randomUUID(),
                user,
                user.toString(),
                java.sql.Timestamp.from(NOW.minusSeconds(2 * 3600)));

        assertThat(planner.planReviewDueReminders(NOW)).isZero();
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("Không nhắc khi quy tắc bị tắt hoặc người học chưa đặt giờ nhắc")
    void skipsDisabledRuleAndUnscheduledLearners() {
        UUID unscheduled = user("ACTIVE");
        preference(unscheduled, true, true, null, "Asia/Ho_Chi_Minh", null, null);
        assertThat(planner.planReviewDueReminders(NOW)).isZero();

        UUID scheduled = user("ACTIVE");
        preference(scheduled, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);
        jdbc.update("UPDATE reminder_rules SET enabled=false WHERE code='REVIEW_DUE'");
        entityManager.clear();
        assertThat(planner.planReviewDueReminders(NOW)).isZero();
        assertThat(count(scheduled)).isZero();
    }

    private int count(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=?", Integer.class, user);
    }

    private String dedupeKey(UUID user) {
        return jdbc.queryForObject(
                "SELECT dedupe_key FROM notifications WHERE user_id=?", String.class, user);
    }

    private void preference(
            UUID user,
            boolean inApp,
            boolean email,
            String reminderTime,
            String zone,
            String quietStart,
            String quietEnd) {
        var preference = new NotificationPreference(user);
        preference.update(
                inApp,
                email,
                reminderTime == null ? null : LocalTime.parse(reminderTime),
                zone,
                quietStart == null ? null : LocalTime.parse(quietStart),
                quietEnd == null ? null : LocalTime.parse(quietEnd),
                NOW);
        preferences.saveAndFlush(preference);
    }

    private UUID user(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Plan', 'Fixture', ?, NOW())",
                id,
                id + "@example.test",
                status);
        return id;
    }
}
