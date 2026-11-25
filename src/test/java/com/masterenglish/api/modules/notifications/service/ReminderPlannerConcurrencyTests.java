package com.masterenglish.api.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.support.ConcurrentRequests;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies concurrent reminder planning and multi-page keyset scanning without an outer test
 * transaction so parallel transactions commit against PostgreSQL and ON CONFLICT DO NOTHING is
 * exercised for real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(
        properties = {
            "notification.scheduler-batch-size=2",
            "notification.mail.enabled=true",
            "notification.mail.from=reminders@example.test",
            "notification.mail.web-base-url=http://localhost:3000"
        })
class ReminderPlannerConcurrencyTests {

    private static final Instant NOW = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired private ReminderPlanner planner;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private LearningEvents learning;

    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID user : createdUsers) {
            jdbc.update(
                    "DELETE FROM notification_deliveries WHERE notification_id IN (SELECT id FROM notifications WHERE user_id = ?)",
                    user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("Hai scheduler chạy song song chỉ tạo đúng 1 notification và 2 deliveries")
    void concurrentSchedulerRunsNeverDuplicateNotification() throws Exception {
        UUID user = createUserAndPref(true, true);
        mockDueCards(5L);

        List<Integer> createdCounts =
                ConcurrentRequests.runAtOnce(2, () -> planner.planReviewDueReminders(NOW));

        assertThat(createdCounts).containsExactlyInAnyOrder(1, 0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE user_id = ?",
                                Integer.class,
                                user))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id WHERE n.user_id = ?",
                                Integer.class,
                                user))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Keyset pagination với batchSize = 2 xử lý hết toàn bộ các trang người học")
    void keysetPaginationProcessesAllPages() {
        UUID u1 = createUserAndPref(true, false);
        UUID u2 = createUserAndPref(true, false);
        UUID u3 = createUserAndPref(true, false);
        mockDueCards(3L);

        int created = planner.planReviewDueReminders(NOW);

        assertThat(created).isEqualTo(3);
        for (UUID user : List.of(u1, u2, u3)) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM notifications WHERE user_id = ?",
                                    Integer.class,
                                    user))
                    .isEqualTo(1);
        }
    }

    private void mockDueCards(long count) {
        when(learning.countDueCards(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Set<UUID> ids = invocation.getArgument(0);
                            return ids.stream()
                                    .collect(Collectors.toMap(Function.identity(), id -> count));
                        });
    }

    private UUID createUserAndPref(boolean inApp, boolean email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, 'fixture-only', 'Conc', 'Planner', 'ACTIVE', NOW())",
                id,
                id + "@example.test");
        createdUsers.add(id);
        var pref = new NotificationPreference(id);
        pref.update(inApp, email, LocalTime.of(20, 0), "Asia/Ho_Chi_Minh", null, null, NOW);
        preferences.saveAndFlush(pref);
        return id;
    }
}
