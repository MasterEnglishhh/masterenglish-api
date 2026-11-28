package com.masterenglish.api.modules.learning.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Completion record for one grammar lesson. The revision is the one the learner completed; {@code
 * hasUpdate} is true when a newer revision is published since then. The record is kept.
 */
public record LessonProgressResponse(
        UUID lessonId,
        String state,
        Instant completedAt,
        UUID lastCompletedRevisionId,
        boolean hasUpdate) {}
