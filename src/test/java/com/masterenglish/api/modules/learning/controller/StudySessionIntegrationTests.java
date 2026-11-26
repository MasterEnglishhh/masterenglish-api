package com.masterenglish.api.modules.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.learning.repository.StudySessionRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import com.masterenglish.api.support.ConcurrentRequests;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end tests for study session creation, 30s-capped heartbeat accounting, anti-multi-tab
 * token/version concurrency, midnight rollover split, and ownership enforcement (UC06).
 */
@Import({TestcontainersConfiguration.class, StudySessionIntegrationTests.ClockConfig.class})
@SpringBootTest
class StudySessionIntegrationTests {

    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");
    private static final UUID SENSE_1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SENSE_2 = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private VocabularySeedService seedService;
    @Autowired private StudySessionRepository studySessions;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private MockMvc mockMvc;
    private final List<UUID> createdUsers = new ArrayList<>();
    private User learner;
    private User otherLearner;
    private User adminOnly;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testStudySessionClock() {
            return new MutableClock(START);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant initial) {
            this(initial, ZoneOffset.UTC);
        }

        private MutableClock(Instant initial, ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant value) {
            instant.set(value);
        }

        void advanceSeconds(long seconds) {
            instant.updateAndGet(t -> t.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(START);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learner = account("study-learner", (short) 1, true);
        otherLearner = account("study-other", (short) 1, true);
        adminOnly = account("study-admin", (short) 2, false);
        seedService.seedVocabularyData(adminOnly.getId());
    }

    @AfterEach
    void cleanUp() {
        for (UUID userId : createdUsers) {
            jdbc.update("DELETE FROM study_sessions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("Kiểm tra xác thực 401, phân quyền 403, CSRF và ràng buộc DTO 400")
    void enforcesAuthCsrfAndValidation() throws Exception {
        String startPayload =
                "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                        + SENSE_1
                        + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}";
        mockMvc.perform(
                        post("/api/v1/study-sessions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(startPayload))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/study-sessions")
                                .with(as(adminOnly))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(startPayload))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/study-sessions")
                                .with(as(learner))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(startPayload))
                .andExpect(status().isForbidden());

        String[] badPayloads = {
            "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                    + SENSE_1
                    + "\",\"lessonId\":\""
                    + UUID.randomUUID()
                    + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
            "{\"sourceType\":\"LESSON\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
            "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                    + SENSE_1
                    + "\",\"timezone\":\"Mars/Base\"}",
            "{\"sourceType\":\"UNKNOWN\",\"wordSenseId\":\""
                    + SENSE_1
                    + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}"
        };
        for (String payload : badPayloads) {
            mockMvc.perform(
                            post("/api/v1/study-sessions")
                                    .with(as(learner))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName(
            "Heartbeat cộng tối đa 30s/nhịp, không cộng khi inactive, từ chối trường lạ và đóng phiên cũ khi mở phiên mới")
    void heartbeatCapsAt30SecondsAndSingleOpenSessionIsEnforced() throws Exception {
        String startResponse =
                mockMvc.perform(
                                post("/api/v1/study-sessions")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                                                        + SENSE_1
                                                        + "\",\"timezone\":\"Asia/Saigon\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                        .andExpect(jsonPath("$.localDay").value("2026-11-19"))
                        .andExpect(jsonPath("$.version").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionId = JsonPath.read(startResponse, "$.sessionId");
        String creditToken = JsonPath.read(startResponse, "$.creditToken");

        // Reject client-supplied seconds field
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"creditToken\":\""
                                                + creditToken
                                                + "\",\"version\":0,\"active\":true,\"seconds\":999}"))
                .andExpect(status().isBadRequest());

        // Advance 20 seconds -> credits 20s
        clock.advanceSeconds(20);
        String hb1 =
                mockMvc.perform(
                                post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"creditToken\":\""
                                                        + creditToken
                                                        + "\",\"version\":0,\"active\":true}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.activeSeconds").value(20.0))
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.todayActiveSeconds").value(20))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat((Number) JsonPath.read(hb1, "$.version")).isEqualTo(1);

        // Advance 90 seconds -> capped to +30s (= 50s)
        clock.advanceSeconds(90);
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"creditToken\":\""
                                                + creditToken
                                                + "\",\"version\":1,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSeconds").value(50.0))
                .andExpect(jsonPath("$.version").value(2));

        // Advance 25 seconds with active=false -> advances timestamp only (+0s), bumps version
        clock.advanceSeconds(25);
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"creditToken\":\""
                                                + creditToken
                                                + "\",\"version\":2,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSeconds").value(50.0))
                .andExpect(jsonPath("$.version").value(3));

        // Other learner cannot heartbeat -> 404
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                .with(as(otherLearner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"creditToken\":\""
                                                + creditToken
                                                + "\",\"version\":3,\"active\":true}"))
                .andExpect(status().isNotFound());

        // Start a second session -> auto-closes the first one and yields fresh creditToken
        clock.advanceSeconds(15);
        String secondStart =
                mockMvc.perform(
                                post("/api/v1/study-sessions")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                                                        + SENSE_2
                                                        + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.todayActiveSeconds").value(50))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String secondSessionId = JsonPath.read(secondStart, "$.sessionId");
        String secondToken = JsonPath.read(secondStart, "$.creditToken");

        // Old session is closed -> 409 CONFLICT
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + sessionId + "/heartbeat")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"creditToken\":\""
                                                + creditToken
                                                + "\",\"version\":3,\"active\":true}"))
                .andExpect(status().isConflict());

        // Close second session cleanly
        mockMvc.perform(
                        post("/api/v1/study-sessions/" + secondSessionId + "/close")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"creditToken\":\"" + secondToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").exists());
    }

    @Test
    @DisplayName("Qua nửa đêm theo múi giờ người học chia tách session và tính đúng cho từng ngày")
    void heartbeatAcrossMidnightSplitsSessionByLocalDay() throws Exception {
        // 2026-11-19 23:59:50 Asia/Ho_Chi_Minh == 16:59:50Z
        clock.set(Instant.parse("2026-11-19T16:59:50Z"));
        String started =
                mockMvc.perform(
                                post("/api/v1/study-sessions")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                                                        + SENSE_1
                                                        + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String firstSessionId = JsonPath.read(started, "$.sessionId");
        String firstToken = JsonPath.read(started, "$.creditToken");

        // Advance 15 seconds -> 2026-11-20 00:00:05 Asia/Ho_Chi_Minh (+10s day 1, +5s day 2)
        clock.advanceSeconds(15);
        String rolloverResponse =
                mockMvc.perform(
                                post("/api/v1/study-sessions/" + firstSessionId + "/heartbeat")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"creditToken\":\""
                                                        + firstToken
                                                        + "\",\"version\":0,\"active\":true}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.rolledOver").value(true))
                        .andExpect(jsonPath("$.localDay").value("2026-11-20"))
                        .andExpect(jsonPath("$.activeSeconds").value(5.0))
                        .andExpect(jsonPath("$.todayActiveSeconds").value(5))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID continuationId = UUID.fromString(JsonPath.read(rolloverResponse, "$.sessionId"));
        assertThat(continuationId).isNotEqualTo(UUID.fromString(firstSessionId));

        var day1 = studySessions.findById(UUID.fromString(firstSessionId)).orElseThrow();
        assertThat(day1.isClosed()).isTrue();
        assertThat(day1.getActiveSeconds()).isEqualByComparingTo(new BigDecimal("10.000"));
        assertThat(day1.getLocalDay().toString()).isEqualTo("2026-11-19");
    }

    @Test
    @DisplayName("Hai heartbeat đồng thời với cùng version chỉ có đúng một request thành công")
    void concurrentDuplicateHeartbeatsCreditOnlyOnce() throws Exception {
        String started =
                mockMvc.perform(
                                post("/api/v1/study-sessions")
                                        .with(as(learner))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                                                        + SENSE_1
                                                        + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String sessionId = JsonPath.read(started, "$.sessionId");
        String creditToken = JsonPath.read(started, "$.creditToken");

        clock.advanceSeconds(20);
        String payload = "{\"creditToken\":\"" + creditToken + "\",\"version\":0,\"active\":true}";

        List<Integer> statuses =
                ConcurrentRequests.runAtOnce(
                        2,
                        () ->
                                mockMvc.perform(
                                                post("/api/v1/study-sessions/"
                                                                + sessionId
                                                                + "/heartbeat")
                                                        .with(as(learner))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(payload))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var saved = studySessions.findById(UUID.fromString(sessionId)).orElseThrow();
        assertThat(saved.getActiveSeconds()).isEqualByComparingTo(new BigDecimal("20.000"));
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối 404 khi wordSenseId, lessonId hoặc attemptId không tồn tại / không thuộc người dùng")
    void missingOrUnownedSourceReturnsNotFound() throws Exception {
        String[] notFoundPayloads = {
            "{\"sourceType\":\"FLASHCARD\",\"wordSenseId\":\""
                    + UUID.randomUUID()
                    + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
            "{\"sourceType\":\"LESSON\",\"lessonId\":\""
                    + UUID.randomUUID()
                    + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
            "{\"sourceType\":\"PRACTICE\",\"attemptId\":\""
                    + UUID.randomUUID()
                    + "\",\"timezone\":\"Asia/Ho_Chi_Minh\"}"
        };
        for (String payload : notFoundPayloads) {
            mockMvc.perform(
                            post("/api/v1/study-sessions")
                                    .with(as(learner))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                    .andExpect(status().isNotFound());
        }
    }

    private User account(String prefix, short roleId, boolean cleanupUser) {
        User user =
                new User(
                        prefix + "-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Study",
                        "Tester");
        user.addRole(entityManager.find(Role.class, roleId));
        user.markEmailVerified(Instant.now());
        User saved = userRepository.saveAndFlush(user);
        if (cleanupUser) {
            createdUsers.add(saved.getId());
        }
        return saved;
    }

    private static RequestPostProcessor as(User account) {
        return user(UserPrincipal.create(account));
    }
}
