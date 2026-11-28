package com.masterenglish.api.modules.learning.dto.response;

import java.util.UUID;

/**
 * Learner progress for one grammar topic. Counts include only lessons that are visible now. {@code
 * hasUpdate} is true when a completed lesson has a newer published revision.
 */
public record TopicProgressResponse(
        UUID topicId,
        String name,
        int completedLessonCount,
        int totalLessonCount,
        boolean hasUpdate) {}
