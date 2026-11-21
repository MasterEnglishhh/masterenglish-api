package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A learner's attempt. While IN_PROGRESS, options carry no correctness flag and questions carry no
 * explanation. Both appear only after the attempt is SUBMITTED.
 */
public record AttemptResponse(
        UUID id,
        String sourceType,
        List<UUID> topicIds,
        String status,
        int totalCount,
        Integer correctCount,
        Integer score,
        Instant startedAt,
        Instant submittedAt,
        List<QuestionResponse> questions) {

    public record QuestionResponse(
            int position,
            UUID topicId,
            String questionType,
            String practiceMode,
            String prompt,
            String explanation,
            UUID selectedOptionId,
            Boolean correct,
            List<OptionResponse> options) {}

    public record OptionResponse(UUID id, int position, String body, Boolean correct) {}
}
