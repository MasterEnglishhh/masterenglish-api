package com.masterenglish.api.modules.practice.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.PracticeEvents.PracticeSummaryView;
import com.masterenglish.api.modules.practice.dto.response.AnswerResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.StatisticsSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface PracticeService {

    ExerciseSummaryResponse exercises(UUID topicId);

    /**
     * {@code sourceType} is GRAMMAR_TOPIC (one {@code topicId}), MIXED_GRAMMAR ({@code topicIds})
     * or MISTAKES ({@code mistakeIds}, the learner's own mistakes).
     */
    StartAttemptResult start(
            UUID userId,
            UUID requestId,
            String sourceType,
            UUID topicId,
            List<UUID> topicIds,
            List<UUID> mistakeIds,
            Integer questionCount);

    /** Grammar entry point {@code POST /api/v1/grammar/topics/{topicId}/attempts}. */
    StartAttemptResult startTopicAttempt(
            UUID userId, UUID topicId, UUID requestId, Integer questionCount);

    /** Grammar entry point {@code POST /api/v1/grammar/attempts/mixed}. */
    StartAttemptResult startMixedAttempt(
            UUID userId, UUID requestId, List<UUID> topicIds, Integer questionCount);

    AttemptResponse getAttempt(UUID userId, UUID attemptId);

    /** Learner's attempt history, newest first, filtered by optional status and source type. */
    PageResponse<AttemptSummaryResponse> listAttempts(
            UUID userId, String status, String sourceType, Pageable pageable);

    AnswerResponse saveAnswer(
            UUID userId,
            UUID attemptId,
            UUID snapshotId,
            UUID selectedOptionId,
            long expectedVersion);

    /** Answer by 1-based question position; the current version of that question is used. */
    AttemptResponse saveAnswer(UUID userId, UUID attemptId, int position, UUID optionId);

    AttemptResponse submit(UUID userId, UUID attemptId);

    List<TopicStatisticsResponse> topicStatistics(UUID userId, String category, String view);

    /** Grammar allocation view, the shape of {@code GET /api/v1/grammar/statistics/topics}. */
    List<TopicStatisticsResponse> topicStatistics(UUID userId);

    StatisticsSummaryResponse statisticsSummary(UUID userId, String category, String view);

    PracticeSummaryView getPracticeSummary(UUID userId, Instant weaknessSince, String timezone);

    boolean existsOwnedAttempt(UUID userId, UUID attemptId);
}
