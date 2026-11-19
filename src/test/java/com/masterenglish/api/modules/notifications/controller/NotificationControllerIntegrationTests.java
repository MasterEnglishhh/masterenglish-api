package com.masterenglish.api.modules.notifications.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
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
class NotificationControllerIntegrationTests {

    private static final Instant BASE = Instant.parse("2026-11-19T08:00:00Z");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private NotificationPreferenceRepository preferences;

    private MockMvc mockMvc;
    private User learner;
    private User otherLearner;
    private User contentAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);
        learner = account("notify-learner", learnerRole);
        otherLearner = account("notify-other", learnerRole);
        contentAdmin = account("notify-admin", adminRole);
    }

    @Test
    @DisplayName("Chưa đăng nhập nhận 401 ở mọi endpoint thông báo")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tài khoản không có vai trò LEARNER nhận 403")
    void nonLearnerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").with(as(contentAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "Feed chỉ gồm thông báo USER có kênh IN_APP của chính mình, mới nhất trước, có phân trang")
    void feedIsOwnInAppUserNotificationsNewestFirst() throws Exception {
        UUID oldest = feedItem(learner, "oldest", BASE);
        UUID middle = feedItem(learner, "middle", BASE.plusSeconds(60));
        UUID newest = feedItem(learner, "newest", BASE.plusSeconds(120));
        internal(learner, BASE.plusSeconds(180));
        emailOnly(learner, BASE.plusSeconds(240));
        feedItem(otherLearner, "other", BASE.plusSeconds(300));

        mockMvc.perform(get("/api/v1/notifications?page=0&size=2").with(as(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.items[1].id").value(middle.toString()))
                .andExpect(jsonPath("$.items[0].type").value("REVIEW_DUE"))
                .andExpect(jsonPath("$.items[0].targetUrl").value("/learn/review"))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/api/v1/notifications?page=1&size=2").with(as(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("Tham số phân trang sai trả 400 ProblemDetail")
    void invalidPagingIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/notifications?size=101").with(as(learner)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications?page=-1").with(as(learner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Số chưa đọc dùng cùng điều kiện lọc với feed")
    void unreadCountMatchesFeedFilter() throws Exception {
        feedItem(learner, "a", BASE);
        UUID read = feedItem(learner, "b", BASE.plusSeconds(60));
        internal(learner, BASE.plusSeconds(120));
        emailOnly(learner, BASE.plusSeconds(180));
        feedItem(otherLearner, "other", BASE);
        mockMvc.perform(
                        post("/api/v1/notifications/" + read + "/read")
                                .with(as(learner))
                                .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(as(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("Đánh dấu đã đọc giữ mốc đọc đầu tiên khi gọi lặp")
    void markReadIsIdempotent() throws Exception {
        UUID id = feedItem(learner, "once", BASE);
        mockMvc.perform(
                        post("/api/v1/notifications/" + id + "/read")
                                .with(as(learner))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        Instant firstRead = reload(id).getReadAt();
        assertThat(firstRead).isNotNull();

        mockMvc.perform(
                        post("/api/v1/notifications/" + id + "/read")
                                .with(as(learner))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(reload(id).getReadAt()).isEqualTo(firstRead);
    }

    @Test
    @DisplayName(
            "Đánh dấu thông báo của người khác, INTERNAL, không có IN_APP hoặc không tồn tại trả 404")
    void markReadOutsideOwnFeedIsNotFound() throws Exception {
        UUID others = feedItem(otherLearner, "other", BASE);
        UUID hidden = internal(learner, BASE);
        UUID emailOnly = emailOnly(learner, BASE);
        for (UUID id : new UUID[] {others, hidden, emailOnly, UUID.randomUUID()}) {
            mockMvc.perform(
                            post("/api/v1/notifications/" + id + "/read")
                                    .with(as(learner))
                                    .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
        assertThat(reload(others).getReadAt()).isNull();
        assertThat(reload(hidden).getReadAt()).isNull();
    }

    @Test
    @DisplayName("Đánh dấu tất cả chỉ ảnh hưởng thông báo trong feed của mình")
    void markAllReadTouchesOnlyOwnFeed() throws Exception {
        UUID first = feedItem(learner, "a", BASE);
        UUID second = feedItem(learner, "b", BASE.plusSeconds(60));
        UUID hidden = internal(learner, BASE);
        UUID others = feedItem(otherLearner, "other", BASE);

        mockMvc.perform(post("/api/v1/notifications/read-all").with(as(learner)).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(reload(first).getReadAt()).isNotNull();
        assertThat(reload(second).getReadAt()).isNotNull();
        assertThat(reload(hidden).getReadAt()).isNull();
        assertThat(reload(others).getReadAt()).isNull();
    }

    @Test
    @DisplayName("POST thiếu CSRF bị từ chối 403")
    void mutationsRequireCsrf() throws Exception {
        UUID id = feedItem(learner, "csrf", BASE);
        mockMvc.perform(post("/api/v1/notifications/" + id + "/read").with(as(learner)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        put("/api/v1/notifications/preferences")
                                .with(as(learner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPreferences()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Lấy cài đặt khi chưa lưu trả giá trị mặc định và không tạo bản ghi")
    void defaultPreferencesAreNotPersisted() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/preferences").with(as(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inAppEnabled").value(true))
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.dailyReminderTime").doesNotExist())
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
        assertThat(preferences.findById(learner.getId())).isEmpty();
    }

    @Test
    @DisplayName("Lưu cài đặt hợp lệ rồi đọc lại đúng giờ dạng HH:mm và quiet hours qua nửa đêm")
    void savesAndReadsPreferences() throws Exception {
        mockMvc.perform(
                        put("/api/v1/notifications/preferences")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validPreferences()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.dailyReminderTime").value("20:00"))
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.quietHoursStart").value("22:30"))
                .andExpect(jsonPath("$.quietHoursEnd").value("07:00"))
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(get("/api/v1/notifications/preferences").with(as(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyReminderTime").value("20:00"))
                .andExpect(jsonPath("$.quietHoursEnd").value("07:00"));
        mockMvc.perform(get("/api/v1/notifications/preferences").with(as(otherLearner)))
                .andExpect(jsonPath("$.emailEnabled").value(false));
    }

    @Test
    @DisplayName(
            "Cài đặt không hợp lệ trả 400: múi giờ, quiet hours, định dạng giờ, thiếu trường, trường lạ")
    void rejectsInvalidPreferences() throws Exception {
        String[] invalid = {
            body("true", "false", "\"20:00\"", "\"Mars/Base\"", "null", "null"),
            body("true", "false", "\"20:00\"", "\"+07:00\"", "null", "null"),
            body("true", "false", "\"20:00\"", "\"Asia/Ho_Chi_Minh\"", "\"22:00\"", "null"),
            body("true", "false", "\"20:00\"", "\"Asia/Ho_Chi_Minh\"", "\"22:00\"", "\"22:00\""),
            body("true", "false", "\"20:00:30\"", "\"Asia/Ho_Chi_Minh\"", "null", "null"),
            body("true", "false", "\"25:00\"", "\"Asia/Ho_Chi_Minh\"", "null", "null"),
            body("null", "false", "null", "\"Asia/Ho_Chi_Minh\"", "null", "null"),
            body("true", "false", "null", "\"\"", "null", "null"),
            "{\"inAppEnabled\":true,\"emailEnabled\":false,\"timezone\":\"Asia/Ho_Chi_Minh\",\"extra\":1}"
        };
        for (String content : invalid) {
            mockMvc.perform(
                            put("/api/v1/notifications/preferences")
                                    .with(as(learner))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        assertThat(preferences.findById(learner.getId())).isEmpty();
    }

    private static String validPreferences() {
        return body("true", "true", "\"20:00\"", "\"Europe/London\"", "\"22:30\"", "\"07:00\"");
    }

    private static String body(
            String inApp, String email, String time, String zone, String start, String end) {
        return "{\"inAppEnabled\":%s,\"emailEnabled\":%s,\"dailyReminderTime\":%s,\"timezone\":%s,\"quietHoursStart\":%s,\"quietHoursEnd\":%s}"
                .formatted(inApp, email, time, zone, start, end);
    }

    private UUID feedItem(User owner, String key, Instant createdAt) {
        UUID id = notification(owner, NotificationVisibility.USER, key, createdAt);
        deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(id, createdAt));
        return id;
    }

    private UUID internal(User owner, Instant createdAt) {
        UUID id = notification(owner, NotificationVisibility.INTERNAL, "internal", createdAt);
        deliveries.saveAndFlush(NotificationDelivery.inAppDelivered(id, createdAt));
        return id;
    }

    private UUID emailOnly(User owner, Instant createdAt) {
        UUID id = notification(owner, NotificationVisibility.USER, "email-only", createdAt);
        deliveries.saveAndFlush(
                NotificationDelivery.emailPending(id, "fixture@example.test", createdAt));
        return id;
    }

    private UUID notification(
            User owner, NotificationVisibility visibility, String key, Instant createdAt) {
        return notifications
                .saveAndFlush(
                        new Notification(
                                owner.getId(),
                                null,
                                NotificationType.REVIEW_DUE,
                                visibility,
                                "Bạn có thẻ cần ôn",
                                "Có 3 thẻ Flashcard đến hạn.",
                                "/learn/review",
                                "TEST:" + key + ":" + UUID.randomUUID(),
                                createdAt))
                .getId();
    }

    private Notification reload(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return notifications.findById(id).orElseThrow();
    }

    private User account(String prefix, Role role) {
        User account =
                new User(
                        prefix + "-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Notify",
                        "Fixture");
        account.addRole(role);
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private static RequestPostProcessor as(User account) {
        return user(UserPrincipal.create(account));
    }
}
