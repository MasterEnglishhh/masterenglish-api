package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code PUT /api/v1/attempts/{attemptId}/answers/{position}}, kept for the grammar
 * clients. Unlike {@link AnswerRequest} it carries no version: the server uses the current one.
 */
public record PositionAnswerRequest(@NotNull UUID optionId) {}
