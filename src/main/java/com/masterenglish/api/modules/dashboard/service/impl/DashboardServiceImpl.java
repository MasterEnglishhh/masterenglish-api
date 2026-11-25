package com.masterenglish.api.modules.dashboard.service.impl;

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
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.DueDeckItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.GrammarLessonProgressSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.GrammarPracticeProgressSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.GrammarWeaknessScopeSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.LearningGoalSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.NotificationSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.OverviewSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.PracticeStatisticsSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.RecentAttemptItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.RecommendationItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.StudyGoalSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.StudyPlanSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.StudyScheduleSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.StudySessionSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.TodayReviewSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.ToeicSkillProgressSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.ToeicTargetSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.ToeicWeaknessScopeSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.VocabularyProgressSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.WeakTopicItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.WeaknessAnalysisSummary;
import com.masterenglish.api.modules.dashboard.service.DashboardService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    static final int WEAKNESS_PERIOD_DAYS = 30;
    static final int WEAKNESS_MIN_SAMPLE = 20;
    static final int WEAKNESS_THRESHOLD_PERCENT = 70;
    static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";
    static final String STATUS_UNAVAILABLE_PENDING_MIGRATION = "UNAVAILABLE_PENDING_MIGRATION";
    private static final int MAX_DUE_DECKS = 5;
    private static final int MAX_WEAK_TOPICS = 5;
    private static final List<Integer> TOEIC_LISTENING_PARTS = List.of(1, 2, 3, 4);
    private static final List<Integer> TOEIC_READING_PARTS = List.of(5, 6, 7);

    private final IdentityEvents identity;
    private final LearningEvents learning;
    private final VocabularyEvents vocabulary;
    private final GrammarEvents grammar;
    private final PracticeEvents practice;
    private final NotificationEvents notifications;
    private final Clock clock;

    public DashboardServiceImpl(
            IdentityEvents identity,
            LearningEvents learning,
            VocabularyEvents vocabulary,
            GrammarEvents grammar,
            PracticeEvents practice,
            NotificationEvents notifications,
            Clock clock) {
        this.identity = Objects.requireNonNull(identity);
        this.learning = Objects.requireNonNull(learning);
        this.vocabulary = Objects.requireNonNull(vocabulary);
        this.grammar = Objects.requireNonNull(grammar);
        this.practice = Objects.requireNonNull(practice);
        this.notifications = Objects.requireNonNull(notifications);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Aggregates cross-module dashboard state. Intentionally runs without an outer transaction so
     * optional module failures (such as notification lookup) never abort the transaction or poison
     * core vocabulary, grammar, and practice reads.
     */
    @Override
    public DashboardResponse getLearnerDashboard(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Instant now = clock.instant();
        ZoneId zone = resolveZone(userId);
        String timezone = zone.getId();
        LocalDate todayLocal = now.atZone(zone).toLocalDate();
        Instant weaknessSince = now.minus(Duration.ofDays(WEAKNESS_PERIOD_DAYS));

        VocabularyProgressView vocab = learning.getVocabularyProgress(userId);
        LessonProgressSummaryView lessonProgress = learning.getLessonProgressSummary(userId);
        List<DueDeckCountView> rawDueDecks = learning.countDueCardsByDeck(userId, now);
        PracticeSummaryView practiceSummary =
                practice.getPracticeSummary(userId, weaknessSince, timezone);
        Optional<LearningGoalView> goal = identity.findLearningGoal(userId);
        NotificationSummary notificationSummary = safeNotificationSummary(userId);

        List<VisibleTopicView> visibleTopics = grammar.listVisibleTopics();
        List<VisibleLessonView> visibleLessons = grammar.listVisibleLessons();

        Set<LocalDate> activityDays =
                new HashSet<>(learning.findActivityLocalDays(userId, timezone));
        activityDays.addAll(practiceSummary.activityLocalDays());
        activityDays.removeIf(day -> day == null || day.isAfter(todayLocal));
        boolean activeToday = activityDays.contains(todayLocal);
        int streakDays = calculateStreak(activityDays, todayLocal);

        List<DueDeckItem> dueDecks = resolveDueDecks(rawDueDecks);
        WeaknessAnalysisSummary weaknessAnalysis =
                buildWeaknessAnalysis(practiceSummary.topicStatsSince(), visibleTopics);
        List<RecommendationItem> recommendations =
                DashboardRecommendationBuilder.build(
                        vocab,
                        dueDecks,
                        weaknessAnalysis.weakTopics(),
                        visibleLessons,
                        lessonProgress);

        Integer plannedDailyMinutes = goal.map(LearningGoalView::dailyMinutes).orElse(null);
        StudyPlanSummary studyPlan =
                buildStudyPlan(goal, plannedDailyMinutes, streakDays, activeToday);
        PracticeStatisticsSummary practiceStatistics =
                buildPracticeStatistics(lessonProgress, visibleLessons.size(), practiceSummary);

        return new DashboardResponse(
                new OverviewSummary(
                        todayLocal, timezone, streakDays, activeToday, activityDays.size()),
                new VocabularyProgressSummary(
                        vocab.studiedSenses(),
                        vocab.masteredSenses(),
                        vocab.dueNow(),
                        vocab.savedSenses(),
                        vocab.totalReviews(),
                        vocab.reviewsLast7Days()),
                new TodayReviewSummary(vocab.dueNow(), vocab.savedSenses(), dueDecks),
                new LearningGoalSummary(
                        goal.isPresent(),
                        goal.map(LearningGoalView::targetScore).orElse(null),
                        plannedDailyMinutes,
                        goal.map(LearningGoalView::targetDate).orElse(null),
                        goal.map(LearningGoalView::updatedAt).orElse(null),
                        streakDays,
                        activeToday,
                        null,
                        false),
                studyPlan,
                practiceStatistics,
                weaknessAnalysis,
                recommendations,
                notificationSummary);
    }

    private ZoneId resolveZone(UUID userId) {
        String rawTimezone;
        try {
            rawTimezone = notifications.findLearnerTimezone(userId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Falling back to default timezone {} after notification lookup error: {}",
                    DEFAULT_TIMEZONE,
                    exception.getClass().getSimpleName());
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        if (rawTimezone == null || rawTimezone.isBlank()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(rawTimezone.trim());
        } catch (DateTimeException exception) {
            log.warn(
                    "Invalid learner timezone encountered on dashboard; falling back to {}",
                    DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }

    private NotificationSummary safeNotificationSummary(UUID userId) {
        try {
            return new NotificationSummary(notifications.countUnreadInFeed(userId), true, "READY");
        } catch (RuntimeException exception) {
            log.warn(
                    "Notification unread count unavailable for dashboard: {}",
                    exception.getClass().getSimpleName());
            return new NotificationSummary(0L, false, "UNAVAILABLE");
        }
    }

    private static StudyPlanSummary buildStudyPlan(
            Optional<LearningGoalView> goal,
            Integer plannedDailyMinutes,
            int streakDays,
            boolean activeToday) {
        StudyGoalSummary studyGoal =
                new StudyGoalSummary(
                        goal.isPresent(),
                        plannedDailyMinutes,
                        goal.map(LearningGoalView::updatedAt).orElse(null));
        ToeicTargetSummary toeicTarget =
                new ToeicTargetSummary(
                        goal.isPresent(),
                        null,
                        goal.map(LearningGoalView::targetScore).orElse(null),
                        goal.map(LearningGoalView::targetDate).orElse(null),
                        null,
                        null,
                        goal.isPresent() ? "TARGET_ONLY_BASELINE_UNAVAILABLE" : "NOT_CONFIGURED");
        StudyScheduleSummary studySchedule =
                new StudyScheduleSummary(
                        false,
                        STATUS_UNAVAILABLE_PENDING_MIGRATION,
                        List.of("MANUAL", "TOEIC_GOAL_PROPOSED"),
                        null,
                        0);
        StudySessionSummary studySession =
                new StudySessionSummary(
                        false,
                        STATUS_UNAVAILABLE_PENDING_MIGRATION,
                        null,
                        plannedDailyMinutes,
                        null,
                        streakDays,
                        activeToday);
        return new StudyPlanSummary(studyGoal, toeicTarget, studySchedule, studySession);
    }

    private static PracticeStatisticsSummary buildPracticeStatistics(
            LessonProgressSummaryView lessonProgress,
            int totalVisibleLessons,
            PracticeSummaryView practiceSummary) {
        String lessonStatus =
                (lessonProgress.completedCount() == 0 && lessonProgress.inProgressCount() == 0)
                        ? "NO_DATA"
                        : "READY";
        String grammarPracticeStatus =
                (practiceSummary.submittedAttemptCount() == 0
                                && practiceSummary.inProgressAttemptCount() == 0)
                        ? "NO_DATA"
                        : "READY";
        List<RecentAttemptItem> recentItems =
                practiceSummary.recentSubmittedAttempts().stream()
                        .map(
                                a ->
                                        new RecentAttemptItem(
                                                a.id(),
                                                a.sourceType(),
                                                a.totalCount(),
                                                a.correctCount(),
                                                a.score(),
                                                a.submittedAt()))
                        .toList();
        return new PracticeStatisticsSummary(
                lessonProgress.completedCount(),
                lessonProgress.inProgressCount(),
                totalVisibleLessons,
                practiceSummary.submittedAttemptCount(),
                practiceSummary.inProgressAttemptCount(),
                practiceSummary.averageScorePercent(),
                false,
                new GrammarLessonProgressSummary(
                        true,
                        lessonStatus,
                        lessonProgress.completedCount(),
                        lessonProgress.inProgressCount(),
                        totalVisibleLessons),
                new GrammarPracticeProgressSummary(
                        true,
                        grammarPracticeStatus,
                        practiceSummary.submittedAttemptCount(),
                        practiceSummary.inProgressAttemptCount(),
                        practiceSummary.averageScorePercent()),
                new ToeicSkillProgressSummary(
                        false,
                        STATUS_UNAVAILABLE_PENDING_MIGRATION,
                        TOEIC_LISTENING_PARTS,
                        0,
                        null),
                new ToeicSkillProgressSummary(
                        false, STATUS_UNAVAILABLE_PENDING_MIGRATION, TOEIC_READING_PARTS, 0, null),
                recentItems);
    }

    private List<DueDeckItem> resolveDueDecks(List<DueDeckCountView> rawDueDecks) {
        if (rawDueDecks.isEmpty()) {
            return List.of();
        }
        Set<UUID> deckIds =
                rawDueDecks.stream().map(DueDeckCountView::deckId).collect(Collectors.toSet());
        Map<UUID, DeckSummaryView> deckMap =
                vocabulary.findActiveDecksByIds(deckIds).stream()
                        .collect(Collectors.toMap(DeckSummaryView::id, Function.identity()));
        return rawDueDecks.stream()
                .filter(d -> deckMap.containsKey(d.deckId()))
                .limit(MAX_DUE_DECKS)
                .map(
                        d -> {
                            DeckSummaryView meta = deckMap.get(d.deckId());
                            return new DueDeckItem(
                                    meta.id(), meta.title(), meta.level(), d.dueCount());
                        })
                .toList();
    }

    private static WeaknessAnalysisSummary buildWeaknessAnalysis(
            List<TopicAccuracyView> stats, List<VisibleTopicView> visibleTopics) {
        Map<UUID, VisibleTopicView> topicById =
                visibleTopics.stream()
                        .collect(Collectors.toMap(VisibleTopicView::id, Function.identity()));

        long totalQuestions = 0;
        long evaluatedTopics = 0;
        long eligibleTopics = 0;
        List<WeakTopicItem> weakTopics = new java.util.ArrayList<>();

        for (TopicAccuracyView stat : stats) {
            VisibleTopicView topic = topicById.get(stat.topicId());
            if (topic == null) {
                continue;
            }
            if (stat.questionCount() > 0) {
                evaluatedTopics++;
            }
            totalQuestions += stat.questionCount();
            if (stat.questionCount() >= WEAKNESS_MIN_SAMPLE) {
                eligibleTopics++;
                if (stat.accuracyPercent() != null
                        && stat.accuracyPercent() < WEAKNESS_THRESHOLD_PERCENT) {
                    weakTopics.add(
                            new WeakTopicItem(
                                    topic.id(),
                                    topic.name(),
                                    topic.slug(),
                                    stat.attemptCount(),
                                    stat.questionCount(),
                                    stat.correctCount(),
                                    stat.accuracyPercent(),
                                    WEAKNESS_PERIOD_DAYS));
                }
            }
        }

        weakTopics.sort(
                Comparator.comparingInt(WeakTopicItem::accuracyPercent)
                        .thenComparing(
                                Comparator.comparingLong(WeakTopicItem::questionCount).reversed())
                        .thenComparing(WeakTopicItem::topicId));
        if (weakTopics.size() > MAX_WEAK_TOPICS) {
            weakTopics = List.copyOf(weakTopics.subList(0, MAX_WEAK_TOPICS));
        } else {
            weakTopics = List.copyOf(weakTopics);
        }

        String status;
        if (!weakTopics.isEmpty()) {
            status = "READY";
        } else if (eligibleTopics > 0) {
            status = "NO_WEAKNESS_DETECTED";
        } else if (totalQuestions > 0) {
            status = "INSUFFICIENT_DATA";
        } else {
            status = "NO_DATA";
        }

        return new WeaknessAnalysisSummary(
                status,
                WEAKNESS_PERIOD_DAYS,
                WEAKNESS_MIN_SAMPLE,
                WEAKNESS_THRESHOLD_PERCENT,
                totalQuestions,
                false,
                new GrammarWeaknessScopeSummary(
                        true, status, evaluatedTopics, eligibleTopics, totalQuestions),
                new ToeicWeaknessScopeSummary(
                        false,
                        STATUS_UNAVAILABLE_PENDING_MIGRATION,
                        TOEIC_LISTENING_PARTS,
                        List.of()),
                new ToeicWeaknessScopeSummary(
                        false,
                        STATUS_UNAVAILABLE_PENDING_MIGRATION,
                        TOEIC_READING_PARTS,
                        List.of()),
                weakTopics);
    }

    /**
     * Counts consecutive local activity days ending today (or yesterday when today has no activity
     * yet so an ongoing streak is preserved until the end of the local day).
     */
    static int calculateStreak(Set<LocalDate> activityDays, LocalDate todayLocal) {
        if (activityDays.isEmpty()) {
            return 0;
        }
        LocalDate cursor = activityDays.contains(todayLocal) ? todayLocal : todayLocal.minusDays(1);
        if (!activityDays.contains(cursor)) {
            return 0;
        }
        int streak = 0;
        while (activityDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }
}
