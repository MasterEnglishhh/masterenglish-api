package com.masterenglish.api.modules.practice.dto.response;

import java.util.UUID;

/** Exercises available for a grammar topic. Counts only published, non-archived questions. */
public record ExerciseSummaryResponse(UUID topicId, long availableQuestions) {}
