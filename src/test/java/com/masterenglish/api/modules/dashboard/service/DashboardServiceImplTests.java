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
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(notifications.findLearnerTimezone(userId)).thenReturn(TZ);
        when(notifications.countUnreadInFeed(userId)).thenReturn(0L);
        when(identity.findLearningGoal(userId)).thenReturn(Optional.empty());
        when(learning.getVocabularyProgress(userId))
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
    @DisplayName("Người học mới chưa có dữ liệu trả trạng thái rỗng và INSUFFICIENT_DATA")
    void emptyDataReturnsInsufficientDataWithoutFakingMetrics() {
        var result = service.getLearnerDashboard(userId);

        assertThat(result.overview().todayLocal()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(result.overview().streakDays()).isZero();
        assertThat(result.overview().activeToday()).isFalse();
        assertThat(result.learningGoal().configured()).isFalse();
        assertThat(result.learningGoal().todayActiveMinutes()).isNull();
        assertThat(result.learningGoal().studyTimeTrackingAvailable()).isFalse();
        assertThat(result.weaknessAnalysis().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.weaknessAnalysis().weakTopics()).isEmpty();
        assertThat(result.recommendations()).isEmpty();
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
        when(learning.getVocabularyProgress(userId))
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
        assertThat(result.todayReview().dueDecks()).hasSize(1);
        assertThat(result.notificationSummary().unreadCount()).isEqualTo(3L);
        assertThat(result.recommendations())
                .extracting(r -> r.type())
                .containsExactly(
                        "FLASHCARD_REVIEW", "DUE_DECK", "WEAK_TOPIC_PRACTICE", "GRAMMAR_LESSON");
    }
}
