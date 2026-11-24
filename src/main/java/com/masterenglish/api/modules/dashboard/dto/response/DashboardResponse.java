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
     * Learning goal & activity streak (M02.06). {@code todayActiveMinutes} stays null while {@code
     * study_sessions} migration is pending Flyway baseline verification.
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

    public record RecentAttemptItem(
            UUID attemptId,
            String sourceType,
            int totalCount,
            Integer correctCount,
            Integer score,
            Instant submittedAt) {}

    /** Grammar lessons & practice attempts summary (M02.02). */
    public record PracticeStatisticsSummary(
            long completedGrammarLessons,
            long inProgressGrammarLessons,
            long totalVisibleGrammarLessons,
            long submittedPracticeAttempts,
            long inProgressPracticeAttempts,
            Integer averagePracticeScorePercent,
            boolean toeicPracticeAvailable,
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

    /**
     * Weakness ranking (M02.04): only topics with >= 20 graded questions in the last 30 days and
     * accuracy < 70% are reported, matching UC06 / B25.
     */
    public record WeaknessAnalysisSummary(
            String status,
            int periodDays,
            int minimumSampleSize,
            int weaknessThresholdPercent,
            long gradedQuestionsInPeriod,
            boolean toeicWeaknessAvailable,
            List<WeakTopicItem> weakTopics) {}

    public record RecommendationItem(
            String type, String title, String description, String targetUrl, int priority) {}

    public record NotificationSummary(long unreadCount) {}
}
