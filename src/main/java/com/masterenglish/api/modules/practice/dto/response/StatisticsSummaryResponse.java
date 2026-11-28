package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;

/**
 * Overall totals across all topics. Each sealed question is counted once, so the total is the real
 * number of graded questions, even when a question belongs to several topics.
 */
public record StatisticsSummaryResponse(
        String view,
        long questionCount,
        long correctCount,
        Integer accuracyPercent,
        long sampleSize,
        long windowCorrectCount,
        Integer windowAccuracyPercent,
        int windowDays,
        Instant windowStart) {}
