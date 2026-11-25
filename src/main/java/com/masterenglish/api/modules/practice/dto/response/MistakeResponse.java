package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A learner's mistake on one question. No answer key and no explanation are included. */
public record MistakeResponse(
        UUID id,
        UUID questionId,
        String state,
        int wrongCount,
        int consecutiveCorrect,
        Instant lastWrongAt,
        Instant lastEvaluatedAt,
        Instant resolvedAt,
        UUID sourceAttemptId) {}
