package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One topic under the allocation view. All-time totals come first; the window fields cover the last
 * {@code windowDays} days by effective finish time. {@code weak} is decided on the window only.
 */
public record TopicStatisticsResponse(
        UUID topicId,
        String view,
        long attemptCount,
        long questionCount,
        long correctCount,
        Integer accuracyPercent,
        long sampleSize,
        long windowCorrectCount,
        Integer windowAccuracyPercent,
        boolean weak,
        int windowDays,
        Instant windowStart) {}
