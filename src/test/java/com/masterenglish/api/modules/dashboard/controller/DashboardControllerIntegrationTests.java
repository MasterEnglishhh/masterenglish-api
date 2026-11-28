package com.masterenglish.api.modules.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.identity.entity.LearningGoal;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.LearningGoalRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.learning.entity.StudySession;
import com.masterenglish.api.modules.learning.entity.StudySourceType;
import com.masterenglish.api.modules.learning.entity.UserSavedWord;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.entity.WordReview;
import com.masterenglish.api.modules.learning.repository.LessonProgressRepository;
import com.masterenglish.api.modules.learning.repository.StudySessionRepository;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.notifications.entity.Notification;
import com.masterenglish.api.modules.notifications.entity.NotificationDelivery;
import com.masterenglish.api.modules.notifications.entity.NotificationPreference;
import com.masterenglish.api.modules.notifications.entity.NotificationType;
import com.masterenglish.api.modules.notifications.entity.NotificationVisibility;
import com.masterenglish.api.modules.notifications.repository.NotificationDeliveryRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationPreferenceRepository;
import com.masterenglish.api.modules.notifications.repository.NotificationRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class DashboardControllerIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LearningGoalRepository goalRepository;
    @Autowired private VocabularySeedService seedService;
    @Autowired private UserWordProgressRepository progressRepository;
    @Autowired private UserSavedWordRepository savedWordRepository;
    @Autowired private WordReviewRepository reviewRepository;
    @Autowired private LessonProgressRepository lessonProgressRepository;
    @Autowired private StudySessionRepository studySessionRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationDeliveryRepository deliveryRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private Clock clock;

    private MockMvc mockMvc;
    private User learner;
    private User otherLearner;
    private User contentAdmin;
    private User systemAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learner = account("dash-learner", (short) 1);
        otherLearner = account("dash-other", (short) 1);
        contentAdmin = account("dash-cadmin", (short) 2);
        systemAdmin = account("dash-sadmin", (short) 3);
    }

    @Test
    @DisplayName("Chưa đăng nhập 401; CONTENT_ADMIN và SYSTEM_ADMIN bị 403 ở /api/v1/dashboard")
    void enforcesLearnerAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/dashboard").with(as(contentAdmin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/dashboard").with(as(systemAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Dashboard khớp 100% số liệu với các module nguồn và tách biệt quyền sở hữu")
    void dashboardMatchesSourceModulesAndIsolatesOwnership() throws Exception {
        Instant now = clock.instant();
        seedService.seedVocabularyData(contentAdmin.getId());

        UUID sense1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID sense2 = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID word1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID rev1 =
                jdbc.queryForObject(
                        "SELECT published_revision_id FROM content_items WHERE id = ?",
                        UUID.class,
                        word1);

        // Learner data
        progressRepository.saveAndFlush(
                new UserWordProgress(
                        learner.getId(),
                        sense1,
                        3,
                        25,
                        new BigDecimal("2.500"),
                        now.minusSeconds(3600),
                        now.minusSeconds(86400),
                        "SM2_3BTN_V1"));
        progressRepository.saveAndFlush(
                new UserWordProgress(
                        learner.getId(),
                        sense2,
                        1,
                        1,
                        new BigDecimal("2.500"),
                        now.plusSeconds(86400),
                        now,
                        "SM2_3BTN_V1"));
        savedWordRepository.saveAndFlush(new UserSavedWord(learner.getId(), sense1));
        reviewRepository.saveAndFlush(
                new WordReview(
                        UUID.randomUUID(),
                        learner.getId(),
                        sense1,
                        word1,
                        rev1,
                        UUID.randomUUID(),
                        "0".repeat(64),
                        "GOOD",
                        "SM2_3BTN_V1",
                        "{}",
                        "{}",
                        now.minusSeconds(3600),
                        now.plusSeconds(3600),
                        now,
                        "Asia/Ho_Chi_Minh",
                        LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Ho_Chi_Minh"))));

        // Goal & timezone
        goalRepository.saveAndFlush(
                new LearningGoal(learner.getId(), (short) 700, 30, LocalDate.of(2027, 6, 1)));
        var pref = new NotificationPreference(learner.getId());
        pref.update(true, false, LocalTime.of(20, 0), "Asia/Ho_Chi_Minh", null, null, now);
        preferenceRepository.saveAndFlush(pref);

        // Study session: 25 active minutes today (< 30 plannedDailyMinutes)
        LocalDate localToday = LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        StudySession todaySession =
                StudySession.open(
                        learner.getId(),
                        StudySourceType.FLASHCARD,
                        sense1,
                        null,
                        null,
                        "Asia/Ho_Chi_Minh",
                        localToday,
                        UUID.randomUUID(),
                        now.minusSeconds(1_600));
        todaySession.recordHeartbeat(now.minusSeconds(100), new BigDecimal("1500.000"));
        todaySession.close(now.minusSeconds(50));
        studySessionRepository.saveAndFlush(todaySession);

        // Grammar topic + 2 lessons (1 completed, 1 unstarted)
        UUID topicId = publishGrammarTopic("Conditionals", "conditionals");
        UUID lessonDone = publishGrammarLesson(topicId, "Zero Conditional", 1);
        UUID lessonTodo = publishGrammarLesson(topicId, "First Conditional", 2);
        UUID lessonRev =
                jdbc.queryForObject(
                        "SELECT published_revision_id FROM content_items WHERE id = ?",
                        UUID.class,
                        lessonDone);
        lessonProgressRepository.upsertCompletion(learner.getId(), lessonDone, lessonRev, now);

        // Submitted grammar attempt with 20 questions (12 correct -> 60% < 70% -> weak topic!)
        seedSubmittedAttempt(learner.getId(), topicId, 20, 12, now);

        // Notifications: 1 USER IN_APP unread + 1 INTERNAL ignored
        var userNotif =
                notificationRepository.saveAndFlush(
                        new Notification(
                                learner.getId(),
                                null,
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Ôn thẻ",
                                "Đến hạn ôn",
                                "/learn/review",
                                "DASH:" + UUID.randomUUID(),
                                now));
        deliveryRepository.saveAndFlush(
                NotificationDelivery.inAppDelivered(userNotif.getId(), now));
        var internalNotif =
                notificationRepository.saveAndFlush(
                        new Notification(
                                learner.getId(),
                                null,
                                NotificationType.SECURITY,
                                NotificationVisibility.INTERNAL,
                                "OTP",
                                "Secret",
                                null,
                                "DASH-INT:" + UUID.randomUUID(),
                                now));
        deliveryRepository.saveAndFlush(
                NotificationDelivery.inAppDelivered(internalNotif.getId(), now));

        // Other learner data (must NEVER leak into learner's dashboard)
        savedWordRepository.saveAndFlush(new UserSavedWord(otherLearner.getId(), sense2));
        var otherNotif =
                notificationRepository.saveAndFlush(
                        new Notification(
                                otherLearner.getId(),
                                null,
                                NotificationType.REVIEW_DUE,
                                NotificationVisibility.USER,
                                "Other",
                                "Other",
                                "/learn/review",
                                "DASH-OTH:" + UUID.randomUUID(),
                                now));
        deliveryRepository.saveAndFlush(
                NotificationDelivery.inAppDelivered(otherNotif.getId(), now));

        // Source APIs
        JsonNode vocabProgress = jsonGet("/api/v1/vocabulary/progress", learner);
        JsonNode goalJson = jsonGet("/api/v1/users/me/goal", learner);
        JsonNode unreadJson = jsonGet("/api/v1/notifications/unread-count", learner);
        JsonNode dashboard = jsonGet("/api/v1/dashboard", learner);

        // Strict cross-module source parity assertions
        assertThat(dashboard.at("/vocabularyProgress/studiedSenses").asLong())
                .isEqualTo(vocabProgress.get("studiedSenses").asLong());
        assertThat(dashboard.at("/vocabularyProgress/masteredSenses").asLong())
                .isEqualTo(vocabProgress.get("masteredSenses").asLong());
        assertThat(dashboard.at("/vocabularyProgress/dueNow").asLong())
                .isEqualTo(vocabProgress.get("dueNow").asLong());
        assertThat(dashboard.at("/vocabularyProgress/savedSenses").asLong())
                .isEqualTo(vocabProgress.get("savedSenses").asLong());
        assertThat(dashboard.at("/vocabularyProgress/totalReviews").asLong())
                .isEqualTo(vocabProgress.get("totalReviews").asLong());
        assertThat(dashboard.at("/vocabularyProgress/reviewsLast7Days").asLong())
                .isEqualTo(vocabProgress.get("reviewsLast7Days").asLong());

        assertThat(dashboard.at("/learningGoal/configured").asBoolean()).isTrue();
        assertThat(dashboard.at("/learningGoal/targetScore").asInt())
                .isEqualTo(goalJson.get("targetScore").asInt());
        assertThat(dashboard.at("/learningGoal/dailyMinutes").asInt())
                .isEqualTo(goalJson.get("dailyMinutes").asInt());
        assertThat(dashboard.at("/learningGoal/streakDays").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.at("/learningGoal/todayActiveMinutes").asInt()).isEqualTo(25);
        assertThat(dashboard.at("/learningGoal/studyTimeTrackingAvailable").asBoolean()).isTrue();

        assertThat(dashboard.at("/studyPlan/studyGoal/plannedDailyMinutes").asInt())
                .isEqualTo(goalJson.get("dailyMinutes").asInt());
        assertThat(dashboard.at("/studyPlan/toeicTarget/targetScore").asInt())
                .isEqualTo(goalJson.get("targetScore").asInt());
        assertThat(dashboard.at("/studyPlan/toeicTarget/currentScore").isNull()).isTrue();
        assertThat(dashboard.at("/studyPlan/studySchedule/available").asBoolean()).isFalse();
        assertThat(dashboard.at("/studyPlan/studySession/trackingAvailable").asBoolean()).isTrue();
        assertThat(dashboard.at("/studyPlan/studySession/status").asText()).isEqualTo("READY");
        assertThat(dashboard.at("/studyPlan/studySession/todayActualMinutes").asInt())
                .isEqualTo(25);
        assertThat(dashboard.at("/studyPlan/studySession/dailyGoalCompleted").asBoolean())
                .isFalse();

        assertThat(dashboard.at("/notificationSummary/unreadCount").asLong())
                .isEqualTo(unreadJson.get("count").asLong())
                .isEqualTo(1L);
        assertThat(dashboard.at("/notificationSummary/available").asBoolean()).isTrue();
        assertThat(dashboard.at("/notificationSummary/status").asText()).isEqualTo("READY");

        assertThat(dashboard.at("/practiceStatistics/completedGrammarLessons").asLong())
                .isEqualTo(1L);
        assertThat(dashboard.at("/practiceStatistics/submittedPracticeAttempts").asLong())
                .isEqualTo(1L);
        assertThat(dashboard.at("/practiceStatistics/averagePracticeScorePercent").asInt())
                .isEqualTo(60);
        assertThat(dashboard.at("/practiceStatistics/grammarLessons/status").asText())
                .isEqualTo("READY");
        assertThat(dashboard.at("/practiceStatistics/grammarPractice/status").asText())
                .isEqualTo("READY");
        assertThat(dashboard.at("/practiceStatistics/toeicListening/status").asText())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");
        assertThat(dashboard.at("/practiceStatistics/toeicReading/status").asText())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");

        assertThat(dashboard.at("/weaknessAnalysis/status").asText()).isEqualTo("READY");
        assertThat(dashboard.at("/weaknessAnalysis/grammar/status").asText()).isEqualTo("READY");
        assertThat(dashboard.at("/weaknessAnalysis/toeicListening/status").asText())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");
        assertThat(dashboard.at("/weaknessAnalysis/toeicReading/status").asText())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");
        assertThat(dashboard.at("/weaknessAnalysis/weakTopics").size()).isEqualTo(1);
        assertThat(dashboard.at("/weaknessAnalysis/weakTopics/0/topicId").asText())
                .isEqualTo(topicId.toString());
        assertThat(dashboard.at("/weaknessAnalysis/weakTopics/0/accuracyPercent").asInt())
                .isEqualTo(60);

        assertThat(dashboard.at("/recommendations").toString())
                .contains("FLASHCARD_REVIEW")
                .doesNotContain("/learn/grammar?");
        assertThat(dashboard.at("/recommendations/0/skill").asText()).isEqualTo("VOCABULARY");
        assertThat(dashboard.at("/recommendations/0/reasonCode").asText())
                .isEqualTo("DUE_FLASHCARDS");
    }

    @Test
    @DisplayName(
            "Weakness loại trừ dữ liệu cũ hơn 30 ngày; streak DST khớp giữa Java và PostgreSQL; timezone hỏng fallback an toàn")
    void weaknessWindowDstBoundaryAndCorruptedTimezoneFallbackWorkOnPostgres() throws Exception {
        Instant now = clock.instant();
        UUID topicOldWeak = publishGrammarTopic("Past Simple", "past-simple");
        seedSubmittedAttempt(learner.getId(), topicOldWeak, 20, 0, now.minusSeconds(31L * 86400));

        // 19 questions within 30 days (< 20 min sample -> INSUFFICIENT_DATA, not READY)
        UUID topicUnderSample = publishGrammarTopic("Prepositions", "prepositions");
        seedSubmittedAttempt(learner.getId(), topicUnderSample, 19, 0, now.minusSeconds(3600));

        // Broken timezone directly in DB must safely fall back to Asia/Ho_Chi_Minh without 500
        jdbc.update(
                "INSERT INTO notification_preferences (user_id, in_app_enabled, email_enabled, timezone, updated_at) VALUES (?, true, false, 'Mars/Base', now())",
                learner.getId());

        JsonNode dashboard = jsonGet("/api/v1/dashboard", learner);
        assertThat(dashboard.at("/overview/timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(dashboard.at("/weaknessAnalysis/status").asText())
                .isEqualTo("INSUFFICIENT_DATA");
        assertThat(dashboard.at("/weaknessAnalysis/gradedQuestionsInPeriod").asLong())
                .isEqualTo(19L);
        assertThat(dashboard.at("/weaknessAnalysis/weakTopics").size()).isZero();
    }

    @Test
    @DisplayName(
            "Activity gộp Learning + Practice ở đúng ranh giới ngày DST America/New_York trên Postgres")
    void combinesLearningAndPracticeDaysAcrossDstBoundaryOnPostgres() throws Exception {
        seedService.seedVocabularyData(contentAdmin.getId());
        UUID word1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID sense1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID rev1 =
                jdbc.queryForObject(
                        "SELECT published_revision_id FROM content_items WHERE id = ?",
                        UUID.class,
                        word1);

        var pref = new NotificationPreference(learner.getId());
        pref.update(
                true, false, LocalTime.of(20, 0), "America/New_York", null, null, clock.instant());
        preferenceRepository.saveAndFlush(pref);

        // Two events on different UTC days that fall on the SAME local day in America/New_York
        Instant morningNy = Instant.parse("2026-03-08T05:30:00Z"); // 2026-03-08 00:30 EST
        Instant lateNightNy = Instant.parse("2026-03-09T03:30:00Z"); // 2026-03-08 23:30 EDT
        reviewRepository.saveAndFlush(
                new WordReview(
                        UUID.randomUUID(),
                        learner.getId(),
                        sense1,
                        word1,
                        rev1,
                        UUID.randomUUID(),
                        "0".repeat(64),
                        "GOOD",
                        "SM2_3BTN_V1",
                        "{}",
                        "{}",
                        morningNy.minusSeconds(3600),
                        morningNy.plusSeconds(3600),
                        morningNy,
                        "America/New_York",
                        LocalDate.of(2026, 3, 8)));
        UUID topic = publishGrammarTopic("DST Topic", "dst-topic");
        seedSubmittedAttempt(learner.getId(), topic, 1, 1, lateNightNy);

        JsonNode dashboard = jsonGet("/api/v1/dashboard", learner);
        assertThat(dashboard.at("/overview/timezone").asText()).isEqualTo("America/New_York");
        // Both fall into local 2026-03-08 (deduped to 1 distinct activity day).
        assertThat(dashboard.at("/overview/totalActivityDays").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Legacy alias Asia/Saigon và raw offset +07:00 không gây 500 trên Postgres AT TIME ZONE khi có dữ liệu học tập")
    void legacyAliasAndStoredRawOffsetWorkAcrossPostgresActivityQueries() throws Exception {
        seedService.seedVocabularyData(contentAdmin.getId());
        UUID word1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID sense1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID rev1 =
                jdbc.queryForObject(
                        "SELECT published_revision_id FROM content_items WHERE id = ?",
                        UUID.class,
                        word1);

        Instant activityAt = clock.instant().minusSeconds(60);
        reviewRepository.saveAndFlush(
                new WordReview(
                        UUID.randomUUID(),
                        learner.getId(),
                        sense1,
                        word1,
                        rev1,
                        UUID.randomUUID(),
                        "1".repeat(64),
                        "GOOD",
                        "SM2_3BTN_V1",
                        "{}",
                        "{}",
                        activityAt.minusSeconds(3600),
                        activityAt.plusSeconds(3600),
                        activityAt,
                        "UTC",
                        LocalDate.ofInstant(activityAt, java.time.ZoneOffset.UTC)));
        UUID topic = publishGrammarTopic("Alias Topic", "alias-topic");
        UUID lesson = publishGrammarLesson(topic, "Alias Lesson", 1);
        lessonProgressRepository.upsertCompletion(
                learner.getId(), lesson, currentRevision(lesson), activityAt);
        seedSubmittedAttempt(learner.getId(), topic, 1, 1, activityAt);

        // 1. PUT /preferences with legacy alias Asia/Saigon -> canonicalized and works on PG
        mockMvc.perform(
                        put("/api/v1/notifications/preferences")
                                .with(as(learner))
                                .with(csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"inAppEnabled\":true,\"emailEnabled\":false,\"contentNotificationsEnabled\":false,\"dailyReminderTime\":\"20:00\",\"timezone\":\"Asia/Saigon\",\"quietHoursStart\":null,\"quietHoursEnd\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"));

        JsonNode fromPut = jsonGet("/api/v1/dashboard", learner);
        assertThat(fromPut.at("/overview/timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(fromPut.at("/overview/totalActivityDays").asLong()).isGreaterThanOrEqualTo(1L);

        // 2. Stored legacy alias directly in DB (before normalization) -> canonicalized on read
        jdbc.update(
                "UPDATE notification_preferences SET timezone = 'Europe/Kiev' WHERE user_id = ?",
                learner.getId());
        entityManager.flush();
        entityManager.clear();
        JsonNode storedAlias = jsonGet("/api/v1/dashboard", learner);
        assertThat(storedAlias.at("/overview/timezone").asText()).isEqualTo("Europe/Kyiv");

        // 3. Stored raw offset directly in DB -> falls back safely to Asia/Ho_Chi_Minh
        jdbc.update(
                "UPDATE notification_preferences SET timezone = '+07:00' WHERE user_id = ?",
                learner.getId());
        entityManager.flush();
        entityManager.clear();
        JsonNode rawOffset = jsonGet("/api/v1/dashboard", learner);
        assertThat(rawOffset.at("/overview/timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
    }

    private JsonNode jsonGet(String path, User actor) throws Exception {
        MvcResult result =
                mockMvc.perform(get(path).with(as(actor))).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID publishGrammarTopic(String name, String slugPrefix) {
        UUID item = publishContentItem("GRAMMAR_TOPIC");
        UUID rev = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_topics (id, published_revision_id, slug, name, difficulty, sort_order) VALUES (?, ?, ?, ?, 'BASIC', 1)",
                item,
                rev,
                slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                name);
        return item;
    }

    private UUID publishGrammarLesson(UUID topicId, String title, int sortOrder) {
        UUID item = publishContentItem("GRAMMAR_LESSON");
        UUID rev = currentRevision(item);
        jdbc.update(
                "INSERT INTO grammar_lessons (id, published_revision_id, topic_id, title, body, sort_order) VALUES (?, ?, ?, ?, '{\"blocks\":[]}'::jsonb, ?)",
                item,
                rev,
                topicId,
                title,
                sortOrder);
        return item;
    }

    /**
     * Seeds a submitted grammar attempt in the baseline shape (V14+). Score is the correct count;
     * the dashboard reads the percentage from correct and total.
     */
    private void seedSubmittedAttempt(
            UUID userId, UUID topicId, int totalCount, int correctCount, Instant submittedAt) {
        UUID attemptId = UUID.randomUUID();
        Instant startedAt = submittedAt.minusSeconds(120);
        java.time.ZoneOffset utc = java.time.ZoneOffset.UTC;
        java.time.LocalDate localDay =
                submittedAt.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        String sourceConfig =
                "{\"sourceType\":\"GRAMMAR_TOPIC\",\"topicIds\":[\""
                        + topicId
                        + "\"],\"questionCount\":"
                        + totalCount
                        + "}";
        jdbc.update(
                "INSERT INTO attempts (id, user_id, category, source_type, source_topic_id,"
                        + " source_config, request_id, request_hash, status, total_count,"
                        + " correct_count, answered_count, score, snapshot_sealed_at, started_at,"
                        + " last_active_at, submitted_at, effective_finished_at, submit_reason,"
                        + " duration_seconds, local_day, version, created_at, updated_at)"
                        + " VALUES (?, ?, 'GRAMMAR', 'GRAMMAR_TOPIC', ?, CAST(? AS jsonb), ?, ?,"
                        + " 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', 120, ?, 0, ?, ?)",
                attemptId,
                userId,
                topicId,
                sourceConfig,
                UUID.randomUUID(),
                "0".repeat(64),
                totalCount,
                correctCount,
                totalCount,
                correctCount,
                startedAt.atOffset(utc),
                startedAt.atOffset(utc),
                startedAt.atOffset(utc),
                submittedAt.atOffset(utc),
                submittedAt.atOffset(utc),
                localDay,
                startedAt.atOffset(utc),
                submittedAt.atOffset(utc));

        String topicIds = "[\"" + topicId + "\"]";
        for (int pos = 1; pos <= totalCount; pos++) {
            UUID qId = publishQuestion(topicId, "Q" + pos);
            UUID snapshotId = UUID.randomUUID();
            UUID rightOpt = UUID.randomUUID();
            UUID wrongOpt = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO attempt_question_snapshots (id, attempt_id, question_id,"
                            + " source_revision_id, category, ordinal, question_type_code,"
                            + " grading_policy_version, question_snapshot, answer_key_snapshot,"
                            + " explanation_snapshot) VALUES (?, ?, ?, ?, 'GRAMMAR', ?,"
                            + " 'SINGLE_CHOICE', 'SINGLE_CHOICE_V1', CAST(? AS jsonb),"
                            + " CAST(? AS jsonb), CAST(? AS jsonb))",
                    snapshotId,
                    attemptId,
                    qId,
                    currentRevision(qId),
                    pos,
                    "{\"prompt\":\"Prompt\",\"practiceMode\":\"STANDARD\","
                            + "\"questionTypeCode\":\"SINGLE_CHOICE\",\"topicIds\":"
                            + topicIds
                            + ",\"attributedTopicId\":\""
                            + topicId
                            + "\"}",
                    "{\"correctOptionId\":\"" + rightOpt + "\"}",
                    "{\"explanation\":\"Expl\"}");
            jdbc.update(
                    "INSERT INTO attempt_snapshot_options (snapshot_id, option_id, position,"
                            + " public_body) VALUES (?, ?, 1, 'Right')",
                    snapshotId,
                    rightOpt);
            jdbc.update(
                    "INSERT INTO attempt_snapshot_options (snapshot_id, option_id, position,"
                            + " public_body) VALUES (?, ?, 2, 'Wrong')",
                    snapshotId,
                    wrongOpt);
            boolean correct = pos <= correctCount;
            UUID chosen = correct ? rightOpt : wrongOpt;
            jdbc.update(
                    "INSERT INTO attempt_answers (id, attempt_id, snapshot_id, selected_option_id,"
                            + " answered_at, version, is_correct, score, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    attemptId,
                    snapshotId,
                    chosen,
                    submittedAt.atOffset(utc),
                    correct,
                    correct ? 1 : 0,
                    startedAt.atOffset(utc),
                    submittedAt.atOffset(utc));
        }
    }

    private UUID publishQuestion(UUID topicId, String prompt) {
        UUID qId = publishContentItem("QUESTION");
        UUID rev = currentRevision(qId);
        jdbc.update(
                "INSERT INTO questions (id, published_revision_id, category, prompt, explanation) VALUES (?, ?, 'GRAMMAR', ?, 'Expl')",
                qId,
                rev,
                prompt);
        jdbc.update(
                "INSERT INTO question_topics (question_id, topic_id) VALUES (?, ?)", qId, topicId);
        return qId;
    }

    private UUID publishContentItem(String type) {
        UUID item = UUID.randomUUID();
        UUID rev = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO content_items (id, content_type, created_by) VALUES (?, ?, ?)",
                item,
                type,
                contentAdmin.getId());
        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, snapshot, status, created_by) VALUES (?, ?, 1, '{}'::jsonb, 'DRAFT', ?)",
                rev,
                item,
                contentAdmin.getId());
        jdbc.update("UPDATE content_items SET published_revision_id = ? WHERE id = ?", rev, item);
        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = now(), published_at = now() WHERE id = ?",
                contentAdmin.getId(),
                rev);
        return item;
    }

    private UUID currentRevision(UUID item) {
        return jdbc.queryForObject(
                "SELECT published_revision_id FROM content_items WHERE id = ?", UUID.class, item);
    }

    private User account(String prefix, short roleId) {
        User account =
                new User(
                        prefix + "-" + UUID.randomUUID() + "@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Dash",
                        "Fixture");
        account.addRole(entityManager.find(Role.class, roleId));
        account.markEmailVerified(Instant.now());
        return userRepository.saveAndFlush(account);
    }

    private static RequestPostProcessor as(User account) {
        return user(UserPrincipal.create(account));
    }
}
