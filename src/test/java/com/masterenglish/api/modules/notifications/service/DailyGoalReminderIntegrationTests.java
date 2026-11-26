package com.masterenglish.api.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.modules.notifications.entity.DeliveryChannel;
import com.masterenglish.api.modules.notifications.entity.DeliveryStatus;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
class DailyGoalReminderIntegrationTests {

    /** 20:00 in Asia/Ho_Chi_Minh. */
    private static final Instant NOW = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired private ReminderPlanner planner;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private LearningEvents learning;

    @Test
    @DisplayName(
            "M10.06: Tạo nhắc DAILY_GOAL cho người học chưa đạt dailyMinutes hôm nay và giữ nguyên dedupe trong ngày")
    void createsDailyGoalReminderWhenGoalNotCompletedAndRespectsDedupe() {
        UUID user = userWithGoal("ACTIVE", 20);
        preference(user, true, true, "20:00", "Asia/Ho_Chi_Minh", null, null);
        when(learning.sumDailyStudySeconds(any())).thenReturn(Map.of(user, 19L * 60L + 59L));

        assertThat(planner.planDailyGoalReminders(NOW)).isEqualTo(1);
        assertThat(planner.planDailyGoalReminders(NOW.plusSeconds(60))).isEqualTo(0);

        var row =
                jdbc.queryForMap(
                        "SELECT id,type,visibility,title,body,target_url,dedupe_key,reminder_rule_id FROM notifications WHERE user_id=?",
                        user);
        assertThat(row.get("type")).isEqualTo("DAILY_GOAL");
        assertThat(row.get("visibility")).isEqualTo("USER");
        assertThat(row.get("title")).isEqualTo("Còn 1 phút để đạt mục tiêu hôm nay");
        assertThat((String) row.get("body")).contains("19/20 phút");
        assertThat(row.get("target_url")).isEqualTo("/learn");
        assertThat(row.get("dedupe_key")).isEqualTo("DAILY_GOAL:" + user + ":2026-11-19");
        assertThat(row.get("reminder_rule_id")).isNotNull();

        UUID id = (UUID) row.get("id");
        assertThat(deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.IN_APP))
                .get()
                .extracting(d -> d.getStatus())
                .isEqualTo(DeliveryStatus.SENT);
        var email =
                deliveries.findByNotificationIdAndChannel(id, DeliveryChannel.EMAIL).orElseThrow();
        assertThat(email.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(email.getRecipientAddress()).isEqualTo(user + "@example.test");
    }

    @Test
    @DisplayName(
            "M10.06: Không nhắc khi đã đạt đủ dailyMinutes hôm nay hoặc chưa đặt learning_goals")
    void skipsLearnersWhoCompletedGoalOrHaveNoGoal() {
        UUID completed = userWithGoal("ACTIVE", 20);
        UUID noGoal = userWithoutGoal("ACTIVE");
        preference(completed, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);
        preference(noGoal, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);
        when(learning.sumDailyStudySeconds(Map.of(completed, LocalDate.of(2026, 11, 19))))
                .thenReturn(Map.of(completed, 20L * 60L));

        assertThat(planner.planDailyGoalReminders(NOW)).isEqualTo(0);
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName(
            "M10.06: Tôn trọng quiet hours, tắt rule DAILY_GOAL, trạng thái không ACTIVE và giờ nhắc chưa tới")
    void respectsRuleEnabledQuietHoursReminderTimeAndActiveStatus() {
        UUID quiet = userWithGoal("ACTIVE", 30);
        UUID early = userWithGoal("ACTIVE", 30);
        UUID locked = userWithGoal("DISABLED", 30);
        UUID due = userWithGoal("ACTIVE", 30);
        preference(quiet, true, false, "20:00", "Asia/Ho_Chi_Minh", "19:30", "21:00");
        preference(early, true, false, "21:00", "Asia/Ho_Chi_Minh", null, null);
        preference(locked, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);
        preference(due, true, false, "20:00", "Asia/Ho_Chi_Minh", null, null);
        when(learning.sumDailyStudySeconds(any())).thenReturn(Map.of());

        jdbc.update("UPDATE reminder_rules SET enabled = false WHERE code = 'DAILY_GOAL'");
        entityManager.clear();
        assertThat(planner.planDailyGoalReminders(NOW)).isEqualTo(0);

        jdbc.update("UPDATE reminder_rules SET enabled = true WHERE code = 'DAILY_GOAL'");
        entityManager.clear();
        assertThat(planner.planDailyGoalReminders(NOW)).isEqualTo(1);
        assertThat(
                        notifications
                                .findFeed(
                                        due, org.springframework.data.domain.PageRequest.of(0, 10))
                                .getTotalElements())
                .isEqualTo(1);
        assertThat(
                        notifications
                                .findFeed(
                                        quiet,
                                        org.springframework.data.domain.PageRequest.of(0, 10))
                                .getTotalElements())
                .isZero();
        assertThat(
                        notifications
                                .findFeed(
                                        early,
                                        org.springframework.data.domain.PageRequest.of(0, 10))
                                .getTotalElements())
                .isZero();
        assertThat(
                        notifications
                                .findFeed(
                                        locked,
                                        org.springframework.data.domain.PageRequest.of(0, 10))
                                .getTotalElements())
                .isZero();
    }

    private UUID userWithGoal(String status, int dailyMinutes) {
        UUID id = userWithoutGoal(status);
        jdbc.update(
                "INSERT INTO learning_goals (user_id, target_score, daily_minutes) VALUES (?, 750, ?)",
                id,
                dailyMinutes);
        return id;
    }

    private UUID userWithoutGoal(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Daily', 'Goal', ?, NOW())",
                id,
                id + "@example.test",
                status);
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 1)", id);
        return id;
    }

    private void preference(
            UUID user,
            boolean inApp,
            boolean email,
            String time,
            String zone,
            String quietStart,
            String quietEnd) {
        var pref = new NotificationPreference(user);
        pref.update(
                inApp,
                email,
                LocalTime.parse(time),
                zone,
                quietStart == null ? null : LocalTime.parse(quietStart),
                quietEnd == null ? null : LocalTime.parse(quietEnd),
                NOW);
        preferences.saveAndFlush(pref);
    }
}
