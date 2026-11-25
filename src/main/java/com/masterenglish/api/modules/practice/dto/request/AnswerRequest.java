package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Autosave body. {@code selectedOptionId = null} clears the answer and still counts as a write.
 * {@code expectedVersion} is the compare-and-set token from the last view of this question.
 */
public record AnswerRequest(UUID selectedOptionId, @NotNull Long expectedVersion) {}
