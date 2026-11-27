package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/grammar/topics/{topicId}/attempts}, kept for the grammar entry point.
 * {@code requestId} makes the start call idempotent: a retry returns the same attempt. A missing
 * {@code questionCount} means 10.
 */
public record GrammarTopicAttemptRequest(
        @NotNull UUID requestId, @Min(1) @Max(50) Integer questionCount) {}
