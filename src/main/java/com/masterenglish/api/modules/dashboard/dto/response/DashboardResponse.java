package com.masterenglish.api.modules.dashboard.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Aggregated learner dashboard payload covering M02.01–M02.06. */
public record DashboardResponse(
        OverviewSummary overview,
        VocabularyProgressSummary vocabularyProgress,
        TodayReviewSummary todayReview,
        LearningGoalSummary learningGoal,
        StudyPlanSummary studyPlan,
        PracticeStatisticsSummary practiceStatistics,
        WeaknessAnalysisSummary weaknessAnalysis,
        List<RecommendationItem> recommendations,
        NotificationSummary notificationSummary) {

    public record OverviewSummary(
            LocalDate todayLocal,
            String timezone,
            int streakDays,
            boolean activeToday,
            long totalActivityDays) {}

    /** Matches GET /api/v1/vocabulary/progress fields identically (M02.01, M02.03). */
    public record VocabularyProgressSummary(
            long studiedSenses,
            long masteredSenses,
            long dueNow,
            long savedSenses,
            long totalReviews,
            long reviewsLast7Days) {}

    public record DueDeckItem(UUID deckId, String title, String level, long dueCount) {}

    public record TodayReviewSummary(long dueNow, long savedSenses, List<DueDeckItem> dueDecks) {}

    /**
     * Backward-compatible goal & streak view (M02.06) backed by {@code study_sessions} real
     * heartbeat accounting.
     */
    public record LearningGoalSummary(
            boolean configured,
            Short targetScore,
            Integer dailyMinutes,
            LocalDate targetDate,
            Instant updatedAt,
            int streakDays,
            boolean activeToday,
            Integer todayActiveMinutes,
            boolean studyTimeTrackingAvailable) {}

    /**
     * Explicit separation of the four approved learner planning concepts (Phương án C): Study Goal,
     * TOEIC Target, Study Schedule, and Study Session. Planned duration never replaces actual
     * tracked study time.
     */
    public record StudyPlanSummary(
            StudyGoalSummary studyGoal,
            ToeicTargetSummary toeicTarget,
            StudyScheduleSummary studySchedule,
            StudySessionSummary studySession) {}

    public record StudyGoalSummary(
            boolean configured, Integer plannedDailyMinutes, Instant updatedAt) {}

    public record ToeicTargetSummary(
            boolean configured,
            Short currentScore,
            Short targetScore,
            LocalDate targetDate,
            Short listeningCurrentScore,
            Short readingCurrentScore,
            String status) {}

    public record StudyScheduleSummary(
            boolean available,
            String status,
            List<String> supportedModes,
            String activeMode,
            int pendingProposalCount) {}

    public record StudySessionSummary(
            boolean trackingAvailable,
            String status,
            Integer todayActualMinutes,
            Integer plannedDailyMinutes,
            Boolean dailyGoalCompleted,
            int streakDays,
            boolean activeToday) {}

    public record RecentAttemptItem(
            UUID attemptId,
            String sourceType,
            int totalCount,
            Integer correctCount,
            Integer score,
            Instant submittedAt) {}

    public record GrammarLessonProgressSummary(
            boolean available,
            String status,
            long completedCount,
            long inProgressCount,
            long totalVisibleCount) {}

    public record GrammarPracticeProgressSummary(
            boolean available,
            String status,
            long submittedAttempts,
            long inProgressAttempts,
            Integer averageScorePercent) {}

    public record ToeicSkillProgressSummary(
            boolean available,
            String status,
            List<Integer> parts,
            long submittedAttempts,
            Integer averageAccuracyPercent) {}

    /**
     * Progress across Grammar lessons, Grammar practice, TOEIC Listening (Parts 1–4), and TOEIC
     * Reading (Parts 5–7) (M02.02). TOEIC skill metrics remain explicitly unavailable until V14
     * migration is approved.
     */
    public record PracticeStatisticsSummary(
            long completedGrammarLessons,
            long inProgressGrammarLessons,
            long totalVisibleGrammarLessons,
            long submittedPracticeAttempts,
            long inProgressPracticeAttempts,
            Integer averagePracticeScorePercent,
            boolean toeicPracticeAvailable,
            GrammarLessonProgressSummary grammarLessons,
            GrammarPracticeProgressSummary grammarPractice,
            ToeicSkillProgressSummary toeicListening,
            ToeicSkillProgressSummary toeicReading,
            List<RecentAttemptItem> recentAttempts) {}

    public record WeakTopicItem(
            UUID topicId,
            String topicName,
            String topicSlug,
            long attemptCount,
            long questionCount,
            long correctCount,
            int accuracyPercent,
            int periodDays) {}

    public record GrammarWeaknessScopeSummary(
            boolean available,
            String status,
            long evaluatedTopics,
            long eligibleTopics,
            long gradedQuestions) {}

    public record ToeicWeaknessScopeSummary(
            boolean available, String status, List<Integer> parts, List<WeakTopicItem> weakParts) {}

    /**
     * Weakness ranking (M02.04): distinguishes NO_DATA (0 graded questions), INSUFFICIENT_DATA
     * (some questions but no topic >= 20 in 30 days), NO_WEAKNESS_DETECTED, and READY (< 70%).
     * TOEIC Listening Parts 1–4 and Reading Parts 5–7 are reported separately as pending schema
     * migration.
     */
    public record WeaknessAnalysisSummary(
            String status,
            int periodDays,
            int minimumSampleSize,
            int weaknessThresholdPercent,
            long gradedQuestionsInPeriod,
            boolean toeicWeaknessAvailable,
            GrammarWeaknessScopeSummary grammar,
            ToeicWeaknessScopeSummary toeicListening,
            ToeicWeaknessScopeSummary toeicReading,
            List<WeakTopicItem> weakTopics) {}

    public record RecommendationItem(
            String type,
            String skill,
            String reasonCode,
            String title,
            String description,
            String targetUrl,
            int priority) {}

    public record NotificationSummary(long unreadCount, boolean available, String status) {
        public NotificationSummary(long unreadCount) {
            this(unreadCount, true, "READY");
        }
    }
}
