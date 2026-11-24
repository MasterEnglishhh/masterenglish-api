package com.masterenglish.api.modules.notifications.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.support.ConcurrentRequests;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies concurrent first-time PUT /api/v1/notifications/preferences without an
 * outer @Transactional so each thread commits its own transaction against PostgreSQL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NotificationPreferencesConcurrencyTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Hai PUT /preferences đồng thời lần đầu đều trả 200 và không lỗi khóa chính")
    void concurrentFirstTimePreferencesUpdateSucceeds() throws Exception {
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        User learner =
                new User(
                        "concurrent-pref-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Concurrent",
                        "Learner");
        learner.addRole(learnerRole);
        learner.markEmailVerified(Instant.now());
        userRepository.saveAndFlush(learner);

        try {
            String body =
                    "{\"inAppEnabled\":true,\"emailEnabled\":true,\"dailyReminderTime\":\"20:00\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"quietHoursStart\":\"22:30\",\"quietHoursEnd\":\"07:00\"}";
            List<Integer> statuses =
                    ConcurrentRequests.runAtOnce(
                            2,
                            () ->
                                    mockMvc.perform(
                                                    put("/api/v1/notifications/preferences")
                                                            .with(
                                                                    user(
                                                                            UserPrincipal.create(
                                                                                    learner)))
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(body))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());

            assertThat(statuses).containsExactly(200, 200);
            assertThat(preferences.findById(learner.getId())).isPresent();
        } finally {
            jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", learner.getId());
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", learner.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", learner.getId());
        }
    }
}
