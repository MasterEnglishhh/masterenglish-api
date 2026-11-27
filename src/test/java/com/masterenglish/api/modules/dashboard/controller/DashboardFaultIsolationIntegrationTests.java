package com.masterenglish.api.modules.dashboard.controller;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies real cross-module fault isolation on PostgreSQL without an outer test transaction: DB
 * persistence failures occurring INSIDE the transactional notification service must roll back only
 * that inner transaction without triggering UnexpectedRollbackException or breaking Dashboard
 * reads.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardFaultIsolationIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LearningGoalRepository goalRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @MockitoSpyBean private NotificationRepository notificationRepository;
    @MockitoSpyBean private NotificationPreferenceRepository preferenceRepository;

    private MockMvc mockMvc;
    private User learner;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        learner =
                new User(
                        "dash-isolation-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Isolation",
                        "Learner");
        learner.addRole(learnerRole);
        learner.markEmailVerified(Instant.now());
        learner = userRepository.saveAndFlush(learner);
        goalRepository.saveAndFlush(
                new LearningGoal(learner.getId(), (short) 720, 45, LocalDate.of(2027, 6, 1)));
    }

    @AfterEach
    void cleanUp() {
        if (learner != null) {
            UUID id = learner.getId();
            jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", id);
            jdbc.update("DELETE FROM learning_goals WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName(
            "Khi repository bên trong @Transactional của NotificationService lỗi DB, GET /api/v1/dashboard không UnexpectedRollbackException và vẫn trả 200 OK")
    void dashboardSurvivesNotificationPersistenceFailuresInsideTransactionalService()
            throws Exception {
        doThrow(new QueryTimeoutException("simulated pg failure on notification_preferences"))
                .when(preferenceRepository)
                .findById(learner.getId());
        doThrow(new QueryTimeoutException("simulated pg failure on notifications"))
                .when(notificationRepository)
                .countUnreadInFeed(learner.getId());

        mockMvc.perform(get("/api/v1/dashboard").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.learningGoal.configured").value(true))
                .andExpect(jsonPath("$.learningGoal.targetScore").value(720))
                .andExpect(jsonPath("$.studyPlan.studyGoal.plannedDailyMinutes").value(45))
                .andExpect(jsonPath("$.notificationSummary.unreadCount").value(0))
                .andExpect(jsonPath("$.notificationSummary.available").value(false))
                .andExpect(jsonPath("$.notificationSummary.status").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName(
            "Khi đọc timezone thành công trên PostgreSQL nhưng đếm unread trong feed lỗi DB, Dashboard vẫn giữ timezone đúng và cô lập notificationSummary")
    void dashboardPreservesTimezoneWhenOnlyUnreadCountFailsInRepository() throws Exception {
        var pref = new NotificationPreference(learner.getId());
        pref.update(
                true, false, LocalTime.of(20, 0), "America/New_York", null, null, Instant.now());
        preferenceRepository.saveAndFlush(pref);

        doThrow(new QueryTimeoutException("simulated pg timeout on notifications count"))
                .when(notificationRepository)
                .countUnreadInFeed(learner.getId());

        mockMvc.perform(get("/api/v1/dashboard").with(user(UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.timezone").value("America/New_York"))
                .andExpect(jsonPath("$.learningGoal.configured").value(true))
                .andExpect(jsonPath("$.learningGoal.targetScore").value(720))
                .andExpect(jsonPath("$.notificationSummary.unreadCount").value(0))
                .andExpect(jsonPath("$.notificationSummary.available").value(false))
                .andExpect(jsonPath("$.notificationSummary.status").value("UNAVAILABLE"));
    }
}
