package com.masterenglish.api.modules.learning.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StudySessionResponse(
        UUID sessionId,
        String sourceType,
        UUID wordSenseId,
        UUID lessonId,
        UUID attemptId,
        String timezone,
        LocalDate localDay,
        UUID creditToken,
        Instant startedAt,
        Instant lastCreditedAt,
        Instant closedAt,
        BigDecimal activeSeconds,
        long version,
        long todayActiveSeconds,
        int todayActiveMinutes,
        boolean rolledOver) {}
