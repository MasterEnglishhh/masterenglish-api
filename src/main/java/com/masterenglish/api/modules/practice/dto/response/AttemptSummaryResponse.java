package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Lightweight summary row for a learner's practice attempt history without snapshot payloads. */
public record AttemptSummaryResponse(
        UUID id,
        String category,
        String sourceType,
        List<UUID> topicIds,
        String status,
        int totalCount,
        Integer correctCount,
        Integer score,
        Instant startedAt,
        Instant submittedAt) {}
