package com.masterenglish.api.modules.practice.dto.response;

import java.util.UUID;

/**
 * Accuracy per grammar topic over the learner's submitted attempts. Unanswered questions count as
 * wrong. {@code accuracyPercent} is null when no question has been shown for the topic.
 */
public record TopicStatisticsResponse(
        UUID topicId,
        long attemptCount,
        long questionCount,
        long correctCount,
        Integer accuracyPercent) {}
