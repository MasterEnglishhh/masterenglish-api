package com.masterenglish.api.modules.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID reviewId,
        UUID wordSenseId,
        String rating,
        int intervalDays,
        BigDecimal easeFactor,
        int repetitions,
        Instant nextDueAt,
        long progressVersion) {}
