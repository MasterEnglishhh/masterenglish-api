package com.masterenglish.api.modules.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonView;
import com.masterenglish.api.common.events.GrammarEvents.VisibleTopicView;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.IdentityEvents.LearningGoalView;
import com.masterenglish.api.common.events.LearningEvents;
import com.masterenglish.api.common.events.LearningEvents.DueDeckCountView;
import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryView;
import com.masterenglish.api.common.events.LearningEvents.VocabularyProgressView;
import com.masterenglish.api.common.events.NotificationEvents;
import com.masterenglish.api.common.events.PracticeEvents;
import com.masterenglish.api.common.events.PracticeEvents.PracticeSummaryView;
import com.masterenglish.api.common.events.PracticeEvents.TopicAccuracyView;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.common.events.VocabularyEvents.DeckSummaryView;
import com.masterenglish.api.config.DashboardProperties;
import com.masterenglish.api.config.DashboardProperties.Recommendations;
import com.masterenglish.api.modules.dashboard.service.impl.DashboardServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DashboardServiceImplTests {

    private static final Instant NOW = Instant.parse("2026-11-24T17:30:00Z");
    // In Asia/Ho_Chi_Minh (+07:00), NOW is 2026-11-25 00:30 local time.
    private static final String TZ = "Asia/Ho_Chi_Minh";

    private final IdentityEvents identity = mock(IdentityEvents.class);
    private final LearningEvents learning = mock(LearningEvents.class);
    private final VocabularyEvents vocabulary = mock(VocabularyEvents.class);
    private final GrammarEvents grammar = mock(GrammarEvents.class);
    private final PracticeEvents practice = mock(PracticeEvents.class);
    private final NotificationEvents notifications = mock(NotificationEvents.class);
    private final UUID userId = UUID.randomUUID();

    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new DashboardServiceImpl(
                        identity,
                        learning,
                        vocabulary,
                        grammar,
                        practice,
                        notifications,
                        new DashboardProperties(new Recommendations(true)),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(notifications.findLearnerTimezone(userId)).thenReturn(TZ);
        when(notifications.countUnreadInFeed(userId)).thenReturn(0L);
        when(identity.findLearningGoal(userId)).thenReturn(Optional.empty());
        when(learning.getVocabularyProgress(userId, NOW))
                .thenReturn(new VocabularyProgressView(0, 0, 0, 0, 0, 0));
        when(learning.getVocabularyProgress(eq(userId), any()))
                .thenReturn(new VocabularyProgressView(0, 0, 0, 0, 0, 0));
        when(learning.getLessonProgressSummary(userId))
                .thenReturn(new LessonProgressSummaryView(0, 0, Set.of(), Set.of()));
        when(learning.countDueCardsByDeck(userId, NOW)).thenReturn(List.of());
        when(learning.findActivityLocalDays(userId, TZ)).thenReturn(Set.of());
        when(grammar.listVisibleTopics()).thenReturn(List.of());
        when(grammar.listVisibleLessons()).thenReturn(List.of());
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(new PracticeSummaryView(0, 0, null, List.of(), List.of(), Set.of()));
    }

    @Test
    @DisplayName(
            "Người học mới chưa có dữ liệu trả trạng thái rỗng, NO_DATA và không giả lập số liệu")
    void emptyDataReturnsNoDataWithoutFakingMetrics() {
        var result = service.getLearnerDashboard(userId);

        assertThat(result.overview().todayLocal()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(result.overview().streakDays()).isZero();
        assertThat(result.overview().activeToday()).isFalse();
        assertThat(result.learningGoal().configured()).isFalse();
        assertThat(result.learningGoal().todayActiveMinutes()).isNull();
        assertThat(result.learningGoal().studyTimeTrackingAvailable()).isFalse();
        assertThat(result.studyPlan().studyGoal().configured()).isFalse();
        assertThat(result.studyPlan().toeicTarget().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.studyPlan().studySchedule().available()).isFalse();
        assertThat(result.studyPlan().studySession().trackingAvailable()).isFalse();
        assertThat(result.studyPlan().studySession().todayActualMinutes()).isNull();
        assertThat(result.practiceStatistics().grammarLessons().status()).isEqualTo("NO_DATA");
        assertThat(result.practiceStatistics().grammarPractice().status()).isEqualTo("NO_DATA");
        assertThat(result.practiceStatistics().toeicListening().available()).isFalse();
        assertThat(result.practiceStatistics().toeicListening().status())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");
        assertThat(result.practiceStatistics().toeicReading().available()).isFalse();
        assertThat(result.practiceStatistics().toeicReading().status())
                .isEqualTo("UNAVAILABLE_PENDING_MIGRATION");
        assertThat(result.weaknessAnalysis().status()).isEqualTo("NO_DATA");
        assertThat(result.weaknessAnalysis().grammar().status()).isEqualTo("NO_DATA");
        assertThat(result.weaknessAnalysis().toeicListening().available()).isFalse();
        assertThat(result.weaknessAnalysis().toeicReading().available()).isFalse();
        assertThat(result.weaknessAnalysis().weakTopics()).isEmpty();
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    @DisplayName("Có bài làm nhưng chưa đủ 20 câu trả INSUFFICIENT_DATA tách bạch NO_DATA")
    void subThresholdQuestionsReturnInsufficientData() {
        UUID topicId = UUID.randomUUID();
        when(grammar.listVisibleTopics())
                .thenReturn(List.of(new VisibleTopicView(topicId, "tenses", "Tenses", "BASIC", 1)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                1,
                                0,
                                50,
                                List.of(),
                                List.of(new TopicAccuracyView(topicId, 1, 12, 6, 50)),
                                Set.of()));

        var result = service.getLearnerDashboard(userId);

        assertThat(result.weaknessAnalysis().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.weaknessAnalysis().grammar().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.weaknessAnalysis().grammar().evaluatedTopics()).isEqualTo(1);
        assertThat(result.weaknessAnalysis().grammar().eligibleTopics()).isZero();
        assertThat(result.weaknessAnalysis().gradedQuestionsInPeriod()).isEqualTo(12);
        assertThat(result.weaknessAnalysis().weakTopics()).isEmpty();
    }

    @Test
    @DisplayName("Điểm yếu yêu cầu tối thiểu 20 câu đã chấm trong 30 ngày và dưới 70%")
    void weaknessThresholdRequires20QuestionsAndUnder70Percent() {
        UUID underSampleTopic = UUID.randomUUID();
        UUID weakTopic = UUID.randomUUID();
        UUID strongTopic = UUID.randomUUID();
        when(grammar.listVisibleTopics())
                .thenReturn(
                        List.of(
                                new VisibleTopicView(
                                        underSampleTopic, "tenses", "Tenses", "BASIC", 1),
                                new VisibleTopicView(
                                        weakTopic, "conditionals", "Conditionals", "BASIC", 2),
                                new VisibleTopicView(
                                        strongTopic, "articles", "Articles", "BASIC", 3)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                4,
                                0,
                                65,
                                List.of(),
                                List.of(
                                        new TopicAccuracyView(underSampleTopic, 1, 19, 2, 11),
                                        new TopicAccuracyView(weakTopic, 2, 20, 13, 65),
                                        new TopicAccuracyView(strongTopic, 1, 20, 14, 70)),
                                Set.of()));

        var result = service.getLearnerDashboard(userId);

        assertThat(result.weaknessAnalysis().status()).isEqualTo("READY");
        assertThat(result.weaknessAnalysis().gradedQuestionsInPeriod()).isEqualTo(59);
        assertThat(result.weaknessAnalysis().weakTopics()).hasSize(1);
        assertThat(result.weaknessAnalysis().weakTopics().getFirst().topicId())
                .isEqualTo(weakTopic);
        assertThat(result.weaknessAnalysis().weakTopics().getFirst().accuracyPercent())
                .isEqualTo(65);
    }

    @Test
    @DisplayName("Đủ mẫu >= 20 câu và mọi chủ điểm >= 70% trả NO_WEAKNESS_DETECTED")
    void sufficientSamplesAboveThresholdReturnsNoWeaknessDetected() {
        UUID strongTopic = UUID.randomUUID();
        when(grammar.listVisibleTopics())
                .thenReturn(
                        List.of(
                                new VisibleTopicView(
                                        strongTopic, "articles", "Articles", "BASIC", 1)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                2,
                                0,
                                80,
                                List.of(),
                                List.of(new TopicAccuracyView(strongTopic, 2, 20, 16, 80)),
                                Set.of()));

        var result = service.getLearnerDashboard(userId);

        assertThat(result.weaknessAnalysis().status()).isEqualTo("NO_WEAKNESS_DETECTED");
        assertThat(result.weaknessAnalysis().weakTopics()).isEmpty();
    }

    @Test
    @DisplayName("Streak tính liên tiếp theo ngày địa phương và giữ chuỗi nếu hôm qua có học")
    void streakCalculatesAcrossTimezoneAndPreservesYesterdayStreak() {
        when(learning.findActivityLocalDays(userId, TZ))
                .thenReturn(Set.of(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 22)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                1,
                                0,
                                90,
                                List.of(),
                                List.of(),
                                Set.of(LocalDate.of(2026, 11, 23))));

        var result = service.getLearnerDashboard(userId);

        assertThat(result.overview().activeToday()).isFalse();
        assertThat(result.overview().streakDays()).isEqualTo(3);
        assertThat(result.overview().totalActivityDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("Gợi ý ưu tiên thẻ đến hạn, deck đến hạn, chủ điểm yếu và bài ngữ pháp đang học")
    void recommendationsFollowDeterministicRules() {
        UUID deckId = UUID.randomUUID();
        UUID lessonDone = UUID.randomUUID();
        UUID lessonInProgress = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();

        when(identity.findLearningGoal(userId))
                .thenReturn(
                        Optional.of(
                                new LearningGoalView(
                                        (short) 750, 30, LocalDate.of(2027, 1, 1), NOW)));
        when(learning.getVocabularyProgress(userId, NOW))
                .thenReturn(new VocabularyProgressView(15, 5, 4, 8, 30, 12));
        when(learning.countDueCardsByDeck(userId, NOW))
                .thenReturn(List.of(new DueDeckCountView(deckId, 4)));
        when(vocabulary.findActiveDecksByIds(Set.of(deckId)))
                .thenReturn(List.of(new DeckSummaryView(deckId, "Office TOEIC", "B1")));
        when(grammar.listVisibleTopics())
                .thenReturn(
                        List.of(
                                new VisibleTopicView(
                                        topicId, "passive", "Passive Voice", "BASIC", 1)));
        when(grammar.listVisibleLessons())
                .thenReturn(
                        List.of(
                                new VisibleLessonView(
                                        lessonDone, topicId, "Done Lesson", 1, (short) 5),
                                new VisibleLessonView(
                                        lessonInProgress, topicId, "Next Lesson", 2, (short) 10)));
        when(learning.getLessonProgressSummary(userId))
                .thenReturn(
                        new LessonProgressSummaryView(
                                1, 1, Set.of(lessonDone), Set.of(lessonInProgress)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                2,
                                0,
                                55,
                                List.of(),
                                List.of(new TopicAccuracyView(topicId, 2, 20, 11, 55)),
                                Set.of(LocalDate.of(2026, 11, 25))));
        when(notifications.countUnreadInFeed(userId)).thenReturn(3L);

        var result = service.getLearnerDashboard(userId);

        assertThat(result.learningGoal().configured()).isTrue();
        assertThat(result.learningGoal().targetScore()).isEqualTo((short) 750);
        assertThat(result.studyPlan().studyGoal().configured()).isTrue();
        assertThat(result.studyPlan().studyGoal().plannedDailyMinutes()).isEqualTo(30);
        assertThat(result.studyPlan().toeicTarget().targetScore()).isEqualTo((short) 750);
        assertThat(result.studyPlan().toeicTarget().currentScore()).isNull();
        assertThat(result.studyPlan().toeicTarget().status())
                .isEqualTo("TARGET_ONLY_BASELINE_UNAVAILABLE");
        assertThat(result.studyPlan().studySession().plannedDailyMinutes()).isEqualTo(30);
        assertThat(result.studyPlan().studySession().todayActualMinutes()).isNull();
        assertThat(result.studyPlan().studySession().dailyGoalCompleted()).isNull();
        assertThat(result.todayReview().dueDecks()).hasSize(1);
        assertThat(result.notificationSummary().unreadCount()).isEqualTo(3L);
        assertThat(result.recommendations())
                .extracting(r -> r.type())
                .containsExactly(
                        "FLASHCARD_REVIEW", "DUE_DECK", "WEAK_TOPIC_PRACTICE", "GRAMMAR_LESSON");
        assertThat(result.recommendations())
                .extracting(r -> r.skill())
                .containsExactly("VOCABULARY", "VOCABULARY", "GRAMMAR", "GRAMMAR");
        assertThat(result.recommendations())
                .extracting(r -> r.reasonCode())
                .containsExactly(
                        "DUE_FLASHCARDS",
                        "DUE_DECK_CARDS",
                        "WEAK_GRAMMAR_TOPIC",
                        "RESUME_GRAMMAR_LESSON");
        assertThat(result.recommendations())
                .extracting(r -> r.targetUrl())
                .containsExactly(
                        "/learn/review",
                        "/learn/review?deck=" + deckId,
                        "/learn/grammar?topic=" + topicId + "&mode=practice",
                        "/learn/grammar?lesson=" + lessonInProgress);
        assertThat(result.notificationSummary().available()).isTrue();
        assertThat(result.notificationSummary().status()).isEqualTo("READY");
    }

    @Test
    @DisplayName("Không tính ngày tương lai, không đếm trùng cùng ngày và streak đứt trả 0")
    void excludesFutureDaysDeduplicatesSameDayAndHandlesBrokenStreak() {
        when(learning.findActivityLocalDays(userId, TZ))
                .thenReturn(Set.of(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                1,
                                0,
                                90,
                                List.of(),
                                List.of(),
                                Set.of(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 27))));

        var sameDayWithFuture = service.getLearnerDashboard(userId);
        assertThat(sameDayWithFuture.overview().activeToday()).isTrue();
        assertThat(sameDayWithFuture.overview().streakDays()).isEqualTo(1);
        assertThat(sameDayWithFuture.overview().totalActivityDays()).isEqualTo(1);

        // Broken streak: only 2 days ago active -> streakDays = 0, totalActivityDays = 1
        when(learning.findActivityLocalDays(userId, TZ))
                .thenReturn(Set.of(LocalDate.of(2026, 11, 23)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(new PracticeSummaryView(0, 0, null, List.of(), List.of(), Set.of()));

        var broken = service.getLearnerDashboard(userId);
        assertThat(broken.overview().activeToday()).isFalse();
        assertThat(broken.overview().streakDays()).isZero();
        assertThat(broken.overview().totalActivityDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("Streak giữ liên tiếp qua ngày đổi giờ DST (23 giờ / 25 giờ)")
    void preservesStreakAcrossDstTransitions() {
        String nyZone = "America/New_York";
        // 2026-03-09T12:00:00Z is 2026-03-09 in New York right after 2026-03-08 spring-forward DST.
        var dstService =
                new DashboardServiceImpl(
                        identity,
                        learning,
                        vocabulary,
                        grammar,
                        practice,
                        notifications,
                        DashboardProperties.defaults(),
                        Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), ZoneOffset.UTC));
        when(notifications.findLearnerTimezone(userId)).thenReturn(nyZone);
        when(learning.findActivityLocalDays(userId, nyZone))
                .thenReturn(Set.of(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(nyZone)))
                .thenReturn(
                        new PracticeSummaryView(
                                1, 0, 85, List.of(), List.of(), Set.of(LocalDate.of(2026, 3, 9))));

        var result = dstService.getLearnerDashboard(userId);

        assertThat(result.overview().todayLocal()).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(result.overview().timezone()).isEqualTo(nyZone);
        assertThat(result.overview().activeToday()).isTrue();
        assertThat(result.overview().streakDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("Timezone không hợp lệ hoặc lỗi từ module Notification không làm hỏng Dashboard")
    void isolatesNotificationFailuresAndInvalidTimezone() {
        when(notifications.findLearnerTimezone(userId)).thenReturn("Mars/Base");
        when(notifications.countUnreadInFeed(userId))
                .thenThrow(new IllegalStateException("notifications unavailable"));
        when(learning.getVocabularyProgress(userId, NOW))
                .thenReturn(new VocabularyProgressView(5, 2, 0, 1, 10, 4));

        var invalidTzResult = service.getLearnerDashboard(userId);
        assertThat(invalidTzResult.overview().timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(invalidTzResult.overview().todayLocal()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(invalidTzResult.vocabularyProgress().studiedSenses()).isEqualTo(5);
        assertThat(invalidTzResult.notificationSummary().available()).isFalse();
        assertThat(invalidTzResult.notificationSummary().status()).isEqualTo("UNAVAILABLE");
        assertThat(invalidTzResult.notificationSummary().unreadCount()).isZero();

        for (String rawOffsetOrUnsupported :
                new String[] {"+07:00", "UTC+07:00", "GMT+07:00", "SystemV/AST4"}) {
            when(notifications.findLearnerTimezone(userId)).thenReturn(rawOffsetOrUnsupported);
            var fallback = service.getLearnerDashboard(userId);
            assertThat(fallback.overview().timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        }

        when(notifications.findLearnerTimezone(userId)).thenReturn("Asia/Saigon");
        var legacyAliasResult = service.getLearnerDashboard(userId);
        assertThat(legacyAliasResult.overview().timezone()).isEqualTo("Asia/Ho_Chi_Minh");

        when(notifications.findLearnerTimezone(userId))
                .thenThrow(new IllegalStateException("timezone lookup down"));
        var throwingTzResult = service.getLearnerDashboard(userId);
        assertThat(throwingTzResult.overview().timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(throwingTzResult.vocabularyProgress().studiedSenses()).isEqualTo(5);
    }

    @Test
    @DisplayName(
            "Weakness giới hạn tối đa 5 chủ điểm yếu và Recommendation fallback sang từ đã lưu khi không có thẻ đến hạn")
    void capsWeakTopicsAndUsesSavedVocabularyFallbackAndMaxLimit() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID t5 = UUID.randomUUID();
        UUID t6 = UUID.randomUUID();
        when(grammar.listVisibleTopics())
                .thenReturn(
                        List.of(
                                new VisibleTopicView(t1, "t1", "Topic 1", "BASIC", 1),
                                new VisibleTopicView(t2, "t2", "Topic 2", "BASIC", 2),
                                new VisibleTopicView(t3, "t3", "Topic 3", "BASIC", 3),
                                new VisibleTopicView(t4, "t4", "Topic 4", "BASIC", 4),
                                new VisibleTopicView(t5, "t5", "Topic 5", "BASIC", 5),
                                new VisibleTopicView(t6, "t6", "Topic 6", "BASIC", 6)));
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(
                        new PracticeSummaryView(
                                6,
                                0,
                                45,
                                List.of(),
                                List.of(
                                        new TopicAccuracyView(t1, 1, 20, 13, 65),
                                        new TopicAccuracyView(t2, 1, 20, 10, 50),
                                        new TopicAccuracyView(t3, 1, 25, 10, 40),
                                        new TopicAccuracyView(t4, 1, 20, 6, 30),
                                        new TopicAccuracyView(t5, 1, 20, 4, 20),
                                        new TopicAccuracyView(t6, 1, 30, 6, 20)),
                                Set.of()));
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        when(grammar.listVisibleLessons())
                .thenReturn(
                        List.of(
                                new VisibleLessonView(l1, t1, "Lesson 1", 1, (short) 5),
                                new VisibleLessonView(l2, t1, "Lesson 2", 2, (short) 5)));
        when(learning.getVocabularyProgress(userId, NOW))
                .thenReturn(new VocabularyProgressView(10, 4, 0, 3, 20, 5));

        var result = service.getLearnerDashboard(userId);

        // Weak topics: capped at 5; t6 (20%, 30q) beats t5 (20%, 20q); t1 (65%) is pushed out.
        assertThat(result.weaknessAnalysis().weakTopics())
                .extracting(w -> w.topicId())
                .containsExactly(t6, t5, t4, t3, t2);
        // Max 6 recommendations and no duplicate targetUrls.
        assertThat(result.recommendations()).hasSize(6);
        assertThat(result.recommendations().stream().map(r -> r.targetUrl()).distinct().count())
                .isEqualTo(6);

        // When no weak topics & no due cards, saved vocabulary fallback routes to
        // /learn/vocabulary?view=saved.
        when(practice.getPracticeSummary(eq(userId), any(), eq(TZ)))
                .thenReturn(new PracticeSummaryView(0, 0, null, List.of(), List.of(), Set.of()));
        var fallbackOnly = service.getLearnerDashboard(userId);
        assertThat(fallbackOnly.recommendations())
                .extracting(r -> r.type())
                .containsExactly("GRAMMAR_LESSON", "GRAMMAR_LESSON", "SAVED_VOCABULARY");
        assertThat(fallbackOnly.recommendations().getLast().targetUrl())
                .isEqualTo("/learn/vocabulary?view=saved");

        // When grammarDeepLinksEnabled = false (default safety gate), unimplemented
        // /learn/grammar?... deep links are suppressed while vocabulary links remain intact.
        var gatedService =
                new DashboardServiceImpl(
                        identity,
                        learning,
                        vocabulary,
                        grammar,
                        practice,
                        notifications,
                        DashboardProperties.defaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        var gatedResult = gatedService.getLearnerDashboard(userId);
        assertThat(gatedResult.recommendations())
                .extracting(r -> r.targetUrl())
                .containsExactly("/learn/vocabulary?view=saved");
    }
}
