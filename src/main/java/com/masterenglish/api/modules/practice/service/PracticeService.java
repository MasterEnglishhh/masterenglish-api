package com.masterenglish.api.modules.practice.service;

import com.masterenglish.api.modules.practice.dto.response.AnswerResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.StatisticsSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import java.util.List;
import java.util.UUID;

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

    AttemptResponse getAttempt(UUID userId, UUID attemptId);

    AnswerResponse saveAnswer(
            UUID userId,
            UUID attemptId,
            UUID snapshotId,
            UUID selectedOptionId,
            long expectedVersion);

    AttemptResponse submit(UUID userId, UUID attemptId);

    List<TopicStatisticsResponse> topicStatistics(UUID userId, String category, String view);

    StatisticsSummaryResponse statisticsSummary(UUID userId, String category, String view);
}
