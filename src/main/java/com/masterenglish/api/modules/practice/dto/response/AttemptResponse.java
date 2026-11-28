package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner view of an attempt. Answers, correctness and explanations appear only after submission
 * (SUBMITTED). Before that, the view carries no answer key and no explanation.
 *
 * <p>{@code sourceType}, {@code topicIds}, {@code position}, {@code questionType}, {@code correct}
 * and {@code id} repeat the names of the grammar contract, so existing clients keep working. They
 * mirror {@code source}, {@code ordinal}, {@code questionTypeCode}, {@code isCorrect} and {@code
 * optionId}.
 */
public record AttemptResponse(
        UUID id,
        String category,
        Source source,
        String sourceType,
        List<UUID> topicIds,
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
            int position,
            UUID topicId,
            String questionTypeCode,
            String questionType,
            String practiceMode,
            String prompt,
            List<OptionView> options,
            UUID selectedOptionId,
            long answerVersion,
            Boolean isCorrect,
            Boolean correct,
            UUID correctOptionId,
            String explanation) {}

    public record OptionView(UUID optionId, UUID id, int position, String body, Boolean correct) {}
}
