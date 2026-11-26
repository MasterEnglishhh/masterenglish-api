package com.masterenglish.api.modules.practice.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.events.PracticeEvents.PracticeSummaryView;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * The shared practice engine for grammar quizzes. Attempts are sealed at start, answers are
 * accepted only while the attempt is open, and grading runs on the server against the snapshot.
 */
public interface PracticeService {

    ExerciseSummaryResponse exercises(UUID topicId);

    StartAttemptResult startTopicAttempt(
            UUID userId, UUID topicId, UUID requestId, Integer questionCount);

    StartAttemptResult startMixedAttempt(
            UUID userId, UUID requestId, List<UUID> topicIds, Integer questionCount);

    AttemptResponse getAttempt(UUID userId, UUID attemptId);

    PageResponse<AttemptSummaryResponse> listAttempts(
            UUID userId, String status, String sourceType, Pageable pageable);

    AttemptResponse saveAnswer(UUID userId, UUID attemptId, int position, UUID optionId);

    /** Submitting twice returns the first result; grading never runs again. */
    AttemptResponse submit(UUID userId, UUID attemptId);

    List<TopicStatisticsResponse> topicStatistics(UUID userId);

    PracticeSummaryView getPracticeSummary(UUID userId, Instant weaknessSince, String timezone);

    boolean existsOwnedAttempt(UUID userId, UUID attemptId);
}
