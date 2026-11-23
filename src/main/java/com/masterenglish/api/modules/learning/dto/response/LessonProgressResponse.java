package com.masterenglish.api.modules.learning.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Completion record for one grammar lesson; the revision is the one the learner completed. */
public record LessonProgressResponse(
        UUID lessonId, String state, Instant completedAt, UUID lastCompletedRevisionId) {}
