package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.notifications.repository.ReminderRuleRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AdminNotificationControllerIntegrationTests {

    private static final Instant BASE = Instant.parse("2026-11-20T09:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private ReminderRuleRepository rules;
    @Autowired private AuditLogRepository auditLogs;

    private MockMvc mockMvc;
    private User learner;
    private User contentAdmin;
    private User systemAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learner = account("admin-notif-learner", (short) 1);
        contentAdmin = account("admin-notif-content", (short) 2);
        systemAdmin = account("admin-notif-system", (short) 3);
    }

    @Test
    @DisplayName("Chưa đăng nhập 401; LEARNER và CONTENT_ADMIN bị 403 ở API quản trị thông báo")
    void enforcesSystemAdminAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notifications/deliveries"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/notifications/deliveries").with(as(learner)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notifications/deliveries").with(as(contentAdmin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notifications/rules").with(as(learner)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notifications/rules").with(as(contentAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("M10.09: SYSTEM_ADMIN lọc được danh sách delivery và không thấy recipientAddress")
    void monitorsDeliveriesWithoutLeakingEmail() throws Exception {
        var rule = rules.findByCode("REVIEW_DUE").orElseThrow();
        var n1 =
                notifications.saveAndFlush(
                        new Notification(
                                learner.getId(),
                                rule.getId(),
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Bạn có 3 thẻ cần ôn hôm nay",
                                "3 thẻ Flashcard đã đến hạn ôn tập.",
                                "/learn/review",
                                "REVIEW_DUE:adm1:" + UUID.randomUUID(),
                                BASE));
        var emailDelivery =
                NotificationDelivery.emailPending(n1.getId(), "secret-learner@example.test", BASE);
        emailDelivery.claim(UUID.randomUUID(), BASE.plusSeconds(60), BASE);
        emailDelivery.markFailed("MAIL_SEND_FAILED", BASE.plusSeconds(10));
        deliveries.saveAndFlush(emailDelivery);
        deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(n1.getId(), BASE));

        mockMvc.perform(
                        get("/api/v1/admin/notifications/deliveries")
                                .param("status", "FAILED")
                                .param("channel", "EMAIL")
                                .param("type", "REVIEW_DUE")
                                .with(as(systemAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].lastFailureCode").value("MAIL_SEND_FAILED"))
                .andExpect(jsonPath("$.items[0].recipientAddress").doesNotExist())
                .andExpect(jsonPath("$.items[0].claimToken").doesNotExist());
    }

    @Test
    @DisplayName("M10.10: SYSTEM_ADMIN xem và cập nhật reminder rule kèm audit log")
    void managesReminderRulesAndRecordsAuditLog() throws Exception {
        var rule = rules.findByCode("REVIEW_DUE").orElseThrow();
        long beforeAudits = auditLogs.count();

        mockMvc.perform(get("/api/v1/admin/notifications/rules").with(as(systemAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("DAILY_GOAL"))
                .andExpect(jsonPath("$[1].code").value("REVIEW_DUE"));

        mockMvc.perform(
                        put("/api/v1/admin/notifications/rules/" + rule.getId())
                                .with(as(systemAdmin))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"minimumGapMinutes\":360}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.minimumGapMinutes").value(360));

        var updated = rules.findById(rule.getId()).orElseThrow();
        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.getMinimumGapMinutes()).isEqualTo(360);
        assertThat(auditLogs.count()).isEqualTo(beforeAudits + 1);

        mockMvc.perform(
                        put("/api/v1/admin/notifications/rules/" + UUID.randomUUID())
                                .with(as(systemAdmin))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true,\"minimumGapMinutes\":60}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(
                        put("/api/v1/admin/notifications/rules/" + rule.getId())
                                .with(as(systemAdmin))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true,\"minimumGapMinutes\":-1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        get("/api/v1/admin/notifications/deliveries")
                                .param("channel", "SMS")
                                .with(as(systemAdmin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("M10.11 Admin: tra cứu lịch sử nhắc học loại bỏ bản ghi SECURITY/INTERNAL")
    void adminReminderHistoryFiltersAndExcludesSecurity() throws Exception {
        var rule = rules.findByCode("REVIEW_DUE").orElseThrow();
        var reminder =
                notifications.saveAndFlush(
                        new Notification(
                                learner.getId(),
                                rule.getId(),
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Bạn có 2 thẻ cần ôn hôm nay",
                                "2 thẻ Flashcard đã đến hạn ôn tập.",
                                "/learn/review",
                                "REVIEW_DUE:admhist:" + UUID.randomUUID(),
                                BASE));
        deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(reminder.getId(), BASE));

        notifications.saveAndFlush(
                new Notification(
                        learner.getId(),
                        null,
                        NotificationType.SECURITY,
                        NotificationVisibility.INTERNAL,
                        "OTP",
                        "Internal",
                        null,
                        "SEC:" + UUID.randomUUID(),
                        BASE));

        mockMvc.perform(
                        get("/api/v1/admin/notifications/history")
                                .param("userId", learner.getId().toString())
                                .param("ruleId", rule.getId().toString())
                                .with(as(systemAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].notificationId").value(reminder.getId().toString()))
                .andExpect(jsonPath("$.items[0].reminderRuleCode").value("REVIEW_DUE"))
                .andExpect(jsonPath("$.items[0].deliveries[0].status").value("SENT"));
    }

    private User account(String prefix, short roleId) {
        User account =
                new User(
                        prefix + "-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Fixture");
        account.addRole(entityManager.find(Role.class, roleId));
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private static RequestPostProcessor as(User account) {
        return user(UserPrincipal.create(account));
    }
}
