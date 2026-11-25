package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner view of an attempt. Answers, correctness and explanations appear only after submission
 * (SUBMITTED). Before that, the view carries no answer key and no explanation.
 */
public record AttemptResponse(
        UUID id,
        String category,
        Source source,
        String status,
        int totalCount,
        long version,
        Instant deadlineAt,
        Instant startedAt,
        Instant lastActiveAt,
        Integer score,
        Integer correctCount,
        Integer answeredCount,
        Integer accuracyPercent,
        Instant submittedAt,
        Instant effectiveFinishedAt,
        String submitReason,
        Integer durationSeconds,
        List<QuestionView> questions) {

    public record Source(String type, List<UUID> topicIds) {}

    public record QuestionView(
            UUID snapshotId,
            int ordinal,
            UUID topicId,
            String questionTypeCode,
            String practiceMode,
            String prompt,
            List<OptionView> options,
            UUID selectedOptionId,
            long answerVersion,
            Boolean isCorrect,
            UUID correctOptionId,
            String explanation) {}

    public record OptionView(UUID optionId, int position, String body) {}
}
