package com.masterenglish.api.modules.dashboard.controller;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.events.NotificationEvents;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies real cross-module fault isolation on PostgreSQL without an outer test transaction: a
 * failing Notification module must not trigger UnexpectedRollbackException or break Dashboard.
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

    @MockitoSpyBean private NotificationEvents notificationEvents;

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
            jdbc.update("DELETE FROM learning_goals WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName(
            "Khi NotificationEvents lỗi lúc runtime, GET /api/v1/dashboard vẫn trả 200 OK với dữ liệu các module còn lại")
    void dashboardSurvivesNotificationFailureAcrossRealTransactions() throws Exception {
        doThrow(new IllegalStateException("notifications outage"))
                .when(notificationEvents)
                .findLearnerTimezone(learner.getId());
        doThrow(new IllegalStateException("notifications outage"))
                .when(notificationEvents)
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
}
